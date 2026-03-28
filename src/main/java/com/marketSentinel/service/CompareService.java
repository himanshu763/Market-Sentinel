package com.marketSentinel.service;

import com.marketSentinel.cache.CacheService;
import com.marketSentinel.matching.MatchScorer;
import com.marketSentinel.matching.MatchScorer.MatchResult;
import com.marketSentinel.matching.MatchScorer.ScoredProduct;
import com.marketSentinel.matching.QueryNormalizer;
import com.marketSentinel.model.CompareResult;
import com.marketSentinel.model.PlatformResult;
import com.marketSentinel.model.ProductIdentity;
import com.marketSentinel.model.RawProduct;
import com.marketSentinel.scraper.AmazonScraper;
import com.marketSentinel.scraper.BlinkitScraper;
import com.marketSentinel.scraper.FlipkartScraper;
import com.marketSentinel.scraper.MyntraScraper;
import com.marketSentinel.scraper.NykaaScraper;
import com.marketSentinel.scraper.ZeptoScraper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class CompareService {

    private static final Logger log = LoggerFactory.getLogger(CompareService.class);

    private final AmazonScraper          amazonScraper;
    private final FlipkartScraper        flipkartScraper;
    private final BlinkitScraper         blinkitScraper;
    private final ZeptoScraper           zeptoScraper;
    private final MyntraScraper          myntraScraper;
    private final NykaaScraper           nykaaScraper;
    private final CacheService           cacheService;
    private final CircuitBreakerRegistry cbRegistry;
    private final QueryNormalizer        queryNormalizer;
    private final MatchScorer            matchScorer;

    public CompareService(AmazonScraper amazonScraper,
                          FlipkartScraper flipkartScraper,
                          BlinkitScraper blinkitScraper,
                          ZeptoScraper zeptoScraper,
                          MyntraScraper myntraScraper,
                          NykaaScraper nykaaScraper,
                          CacheService cacheService,
                          CircuitBreakerRegistry cbRegistry,
                          QueryNormalizer queryNormalizer,
                          MatchScorer matchScorer) {
        this.amazonScraper   = amazonScraper;
        this.flipkartScraper = flipkartScraper;
        this.blinkitScraper  = blinkitScraper;
        this.zeptoScraper    = zeptoScraper;
        this.myntraScraper   = myntraScraper;
        this.nykaaScraper    = nykaaScraper;
        this.cacheService    = cacheService;
        this.cbRegistry      = cbRegistry;
        this.queryNormalizer = queryNormalizer;
        this.matchScorer     = matchScorer;
    }

    public CompareResult compare(ProductIdentity identity) {

        // ─── Phase 1: Scrape source platform to get product name ─────────────
        RawProduct sourceProduct = scrapeSource(identity);

        String rawTitle = sourceProduct != null
                ? sourceProduct.getTitle()
                : identity.getProductId();

        String searchQuery = queryNormalizer.normalize(rawTitle);

        log.info("Source: {} → raw: '{}' → query: '{}'",
                identity.getPlatform(), rawTitle, searchQuery);

        // ─── Phase 2: Fan out to ALL other platforms in parallel ─────────────
        String src = identity.getPlatform();
        String pid = identity.getProductId();

        // Source platform result (already have it or direct scrape)
        Map<CompletableFuture<PlatformResult>, String> singleFutures = new LinkedHashMap<>();
        if (sourceProduct != null) {
            singleFutures.put(CompletableFuture.completedFuture(
                    PlatformResult.success(src, sourceProduct)), src);
        }

        // Cross-platform search: every platform except source
        Map<CompletableFuture<List<PlatformResult>>, String> scoredFutures = new LinkedHashMap<>();

        if (!"amazon".equals(src)) {
            scoredFutures.put(scrapeAndScore("amazon", "amazon:search:" + pid,
                    () -> amazonScraper.search(searchQuery), sourceProduct), "amazon");
        }

        if (!"flipkart".equals(src)) {
            scoredFutures.put(scrapeAndScore("flipkart", "flipkart:search:" + pid,
                    () -> flipkartScraper.search(searchQuery), sourceProduct), "flipkart");
        }

        if (!"blinkit".equals(src)) {
            scoredFutures.put(scrapeAndScore("blinkit", "blinkit:search:" + pid,
                    () -> blinkitScraper.scrape(searchQuery), sourceProduct), "blinkit");
        }

        if (!"zepto".equals(src)) {
            scoredFutures.put(scrapeAndScore("zepto", "zepto:search:" + pid,
                    () -> zeptoScraper.scrape(searchQuery), sourceProduct), "zepto");
        }

        if (!"myntra".equals(src)) {
            scoredFutures.put(scrapeAndScore("myntra", "myntra:search:" + pid,
                    () -> myntraScraper.search(searchQuery), sourceProduct), "myntra");
        }

        if (!"nykaa".equals(src)) {
            scoredFutures.put(scrapeAndScore("nykaa", "nykaa:search:" + pid,
                    () -> nykaaScraper.search(searchQuery), sourceProduct), "nykaa");
        }

        // ─── Wait for all (5s hard timeout) ──────────────────────────────────
        List<CompletableFuture<?>> allFutures = new ArrayList<>();
        allFutures.addAll(singleFutures.keySet());
        allFutures.addAll(scoredFutures.keySet());

        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                allFutures.toArray(new CompletableFuture[0])
        );
        try {
            allDone.get(5000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Some platforms did not respond within 5s");
        }

        // ─── Collect & partition: exact / similar / mismatch ─────────────────
        List<PlatformResult> exactMatches    = new ArrayList<>();
        List<PlatformResult> similarProducts = new ArrayList<>();

        // Source platform result
        for (Map.Entry<CompletableFuture<PlatformResult>, String> entry : singleFutures.entrySet()) {
            CompletableFuture<PlatformResult> future = entry.getKey();
            String platform = entry.getValue();
            if (future.isDone()) {
                try {
                    exactMatches.add(future.join());
                } catch (Exception e) {
                    exactMatches.add(PlatformResult.error(platform, e.getMessage()));
                }
            } else {
                exactMatches.add(PlatformResult.timeout(platform));
            }
        }

        // Scored cross-platform results
        for (Map.Entry<CompletableFuture<List<PlatformResult>>, String> entry : scoredFutures.entrySet()) {
            CompletableFuture<List<PlatformResult>> future = entry.getKey();
            String platform = entry.getValue();
            if (future.isDone()) {
                try {
                    for (PlatformResult pr : future.join()) {
                        String type = pr.getMatchType() != null ? pr.getMatchType() : "";
                        switch (type) {
                            case "exact"   -> exactMatches.add(pr);
                            case "similar" -> similarProducts.add(pr);
                            default -> {
                                // error/timeout results go to exactMatches for visibility
                                if (!"success".equals(pr.getStatus())) {
                                    exactMatches.add(pr);
                                } else {
                                    log.debug("Dropping mismatch: {} on {}", pr.getProduct().getTitle(), platform);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    exactMatches.add(PlatformResult.error(platform, e.getMessage()));
                }
            } else {
                exactMatches.add(PlatformResult.timeout(platform));
            }
        }

        String status = exactMatches.stream().allMatch(r -> "success".equals(r.getStatus()))
                ? "complete" : "partial";
        return new CompareResult(identity.getOriginalUrl(), exactMatches, status, similarProducts);
    }

    // ─── Phase 1: Scrape source platform synchronously ──────────────────────

    private RawProduct scrapeSource(ProductIdentity identity) {
        try {
            String cacheKey = identity.getPlatform() + ":" + identity.getProductId();
            Optional<RawProduct> cached = cacheService.get(cacheKey);
            if (cached.isPresent()) {
                log.info("Source cache HIT: {}", cacheKey);
                return cached.get();
            }

            return switch (identity.getPlatform()) {
                case "amazon" -> amazonScraper.scrape(identity.getProductId());
                case "flipkart" -> flipkartScraper.scrape(identity.getProductId(), identity.getOriginalUrl());
                case "myntra"   -> myntraScraper.scrape(identity.getProductId(), identity.getOriginalUrl());
                case "nykaa"    -> nykaaScraper.scrape(identity.getProductId(), identity.getOriginalUrl());
                case "blinkit" -> {
                    String name = extractNameFromUrlSlug(identity.getOriginalUrl(), "prn");
                    yield name != null
                            ? new RawProduct(name, "N/A", "blinkit", identity.getOriginalUrl())
                            : null;
                }
                case "zepto" -> {
                    String name = extractNameFromUrlSlug(identity.getOriginalUrl(), "pn");
                    yield name != null
                            ? new RawProduct(name, "N/A", "zepto", identity.getOriginalUrl())
                            : null;
                }
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Failed to scrape source platform {}: {}", identity.getPlatform(), e.getMessage());
            return null;
        }
    }

    private String extractNameFromUrlSlug(String url, String marker) {
        try {
            String path = URI.create(url).getPath();
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals(marker) && i + 1 < parts.length) {
                    return parts[i + 1].replace("-", " ");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract name from URL: {}", url);
        }
        return null;
    }

    // ─── Multi-result scrape + score: fetch candidates, rank by match ───────

    private CompletableFuture<List<PlatformResult>> scrapeAndScore(
            String platform,
            String cacheKey,
            CheckedSupplier<List<RawProduct>> fn,
            RawProduct sourceProduct) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Cache check — cached product was already the best match from a previous run
                Optional<RawProduct> cached = cacheService.get(cacheKey);
                if (cached.isPresent()) {
                    log.info("Cache HIT: {}", cacheKey);
                    if (sourceProduct != null) {
                        MatchResult mr = matchScorer.score(sourceProduct, cached.get());
                        return List.of(PlatformResult.success(platform, cached.get(),
                                mr.confidence(), mr.matchType()));
                    }
                    return List.of(PlatformResult.success(platform, cached.get()));
                }
                log.info("Cache MISS: {}", cacheKey);

                CircuitBreaker cb = cbRegistry.circuitBreaker(platform);
                List<RawProduct> candidates = CircuitBreaker.decorateCheckedSupplier(cb, fn).get();

                if (candidates.isEmpty()) {
                    log.info("{}: no search results", platform);
                    return List.of(PlatformResult.error(platform, "No results from " + platform));
                }

                if (sourceProduct == null) {
                    RawProduct best = candidates.get(0);
                    cacheService.put(cacheKey, best, ttlFor(platform));
                    return List.of(PlatformResult.success(platform, best));
                }

                // Score all candidates against source product
                List<ScoredProduct> scored = matchScorer.scoreAll(sourceProduct, candidates);

                log.info("{}: scored {} candidates. Best: '{}' (confidence={}, type={})",
                        platform, scored.size(),
                        scored.get(0).product().getTitle(),
                        scored.get(0).result().confidence(),
                        scored.get(0).result().matchType());

                // Cache the best match
                cacheService.put(cacheKey, scored.get(0).product(), ttlFor(platform));

                // Return: best exact match + up to 2 similar products
                List<PlatformResult> results = new ArrayList<>();
                boolean exactFound = false;

                for (ScoredProduct sp : scored) {
                    MatchResult mr = sp.result();
                    if (MatchResult.EXACT.equals(mr.matchType()) && !exactFound) {
                        results.add(PlatformResult.success(platform, sp.product(),
                                mr.confidence(), mr.matchType()));
                        exactFound = true;
                    } else if (MatchResult.SIMILAR.equals(mr.matchType()) && results.size() < 3) {
                        results.add(PlatformResult.success(platform, sp.product(),
                                mr.confidence(), mr.matchType()));
                    }
                }

                if (results.isEmpty()) {
                    log.info("{}: no products met match threshold", platform);
                    results.add(PlatformResult.error(platform,
                            "No matching product found on " + platform));
                }

                return results;

            } catch (CallNotPermittedException e) {
                log.warn("{} circuit breaker OPEN — failing fast", platform);
                return List.of(PlatformResult.error(platform, platform + " is temporarily unavailable"));
            } catch (Throwable e) {
                log.warn("{} scrape+score failed: {}", platform, e.getMessage());
                return List.of(PlatformResult.error(platform, e.getMessage()));
            }
        });
    }

    // ─── Per-platform TTL (design spec §4.2) ─────────────────────────────────

    private Duration ttlFor(String platform) {
        return switch (platform) {
            case "blinkit", "zepto" -> Duration.ofMinutes(30);
            case "myntra", "nykaa"  -> Duration.ofHours(6);
            default                 -> Duration.ofHours(2);  // amazon, flipkart
        };
    }
}
