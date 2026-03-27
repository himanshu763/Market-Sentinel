package com.marketSentinel.service;

import com.marketSentinel.cache.CacheService;
import com.marketSentinel.model.CompareResult;
import com.marketSentinel.model.ProductIdentity;
import com.marketSentinel.model.RawProduct;
import com.marketSentinel.scraper.AmazonScraper;
import com.marketSentinel.scraper.FlipkartScraper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class CompareService {

    private final AmazonScraper amazonScraper;
    private final FlipkartScraper flipkartScraper;
    private final CacheService cacheService;

    public CompareService(AmazonScraper amazonScraper,
                          FlipkartScraper flipkartScraper,
                          CacheService cacheService) {
        this.amazonScraper = amazonScraper;
        this.flipkartScraper = flipkartScraper;
        this.cacheService = cacheService;
    }

    public CompareResult compare(ProductIdentity identity) {

        List<CompletableFuture<Optional<RawProduct>>> futures = new ArrayList<>();

        // Launch Amazon scrape in background thread
        futures.add(scrapeAmazon(identity));

        // Launch Flipkart scrape in background thread at the same time
        futures.add(scrapeFlipkart(identity));

        // Wait for ALL futures to finish (max 8 seconds)
        // If a platform takes longer than 8s, it's marked as timed out
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        try {
            allDone.get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Some platforms timed out — that's okay, return what we have
            System.out.println("Some platforms timed out: " + e.getMessage());
        }

        // Collect results from all futures that completed
        List<RawProduct> results = new ArrayList<>();
        for (CompletableFuture<Optional<RawProduct>> future : futures) {
            if (future.isDone()) {
                future.join().ifPresent(results::add);
            }
        }

        String status = results.size() == futures.size() ? "complete" : "partial";
        return new CompareResult(identity.getOriginalUrl(), results, status);
    }

    // Runs Amazon scrape in a separate thread
    private CompletableFuture<Optional<RawProduct>> scrapeAmazon(ProductIdentity identity) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String cacheKey = "amazon:" + identity.getProductId();

                // Check cache first
                Optional<RawProduct> cached = cacheService.get(cacheKey);
                if (cached.isPresent()) {
                    System.out.println("Cache HIT: " + cacheKey);
                    return cached;
                }

                System.out.println("Cache MISS: " + cacheKey + " — scraping Amazon");
                RawProduct product = amazonScraper.scrape(identity.getProductId());
                cacheService.put(cacheKey, product, Duration.ofHours(2));
                return Optional.of(product);

            } catch (Exception e) {
                System.out.println("Amazon scrape failed: " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    // Runs Flipkart scrape in a separate thread
    private CompletableFuture<Optional<RawProduct>> scrapeFlipkart(ProductIdentity identity) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String cacheKey = "flipkart:" + identity.getProductId();

                Optional<RawProduct> cached = cacheService.get(cacheKey);
                if (cached.isPresent()) {
                    System.out.println("Cache HIT: " + cacheKey);
                    return cached;
                }

                System.out.println("Cache MISS: " + cacheKey + " — scraping Flipkart");
                RawProduct product = flipkartScraper.scrape(
                        identity.getProductId(),
                        identity.getOriginalUrl()
                );
                cacheService.put(cacheKey, product, Duration.ofHours(2));
                return Optional.of(product);

            } catch (Exception e) {
                // Flipkart blocked — return a placeholder, don't crash
                System.out.println("Flipkart scrape failed: " + e.getMessage());
                return Optional.of(new RawProduct(
                        "Unavailable",
                        "Blocked — proxy needed",
                        "flipkart",
                        identity.getOriginalUrl()
                ));
            }
        });
    }
}