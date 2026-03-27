package com.marketSentinel.api;

import com.marketSentinel.cache.CacheService;
import com.marketSentinel.model.CompareRequest;
import com.marketSentinel.model.ProductIdentity;
import com.marketSentinel.model.RawProduct;
import com.marketSentinel.parser.UrlParser;
import com.marketSentinel.scraper.AmazonScraper;
import com.marketSentinel.scraper.FlipkartScraper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class CompareController {

    private final UrlParser urlParser;
    private final AmazonScraper amazonScraper;
    private final FlipkartScraper flipkartScraper;
    private final CacheService cacheService;

    public CompareController(UrlParser urlParser,
                             AmazonScraper amazonScraper,
                             FlipkartScraper flipkartScraper,
                             CacheService cacheService) {
        this.urlParser = urlParser;
        this.amazonScraper = amazonScraper;
        this.flipkartScraper = flipkartScraper;
        this.cacheService = cacheService;
    }

    @PostMapping("/compare")
    public ResponseEntity<?> compare(@RequestBody CompareRequest request) {

        ProductIdentity identity = urlParser.parse(request.getUrl());

        // Build a cache key from platform + productId
        // e.g. "amazon:B0CHX3QBCH"
        String cacheKey = identity.getPlatform() + ":" + identity.getProductId();

        // Check cache first — if found, return immediately (< 200ms)
        Optional<RawProduct> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            System.out.println("Cache HIT for: " + cacheKey);
            return ResponseEntity.ok(cached.get());
        }

        System.out.println("Cache MISS for: " + cacheKey + " — scraping live");

        // Cache miss — scrape live
        switch (identity.getPlatform()) {
            case "amazon" -> {
                RawProduct product = amazonScraper.scrape(identity.getProductId());

                // Save to cache for 2 hours
                cacheService.put(cacheKey, product, Duration.ofHours(2));
                return ResponseEntity.ok(product);
            }
            case "flipkart" -> {
                try {
                    RawProduct product = flipkartScraper.scrape(
                            identity.getProductId(),
                            identity.getOriginalUrl()
                    );
                    cacheService.put(cacheKey, product, Duration.ofHours(2));
                    return ResponseEntity.ok(product);
                } catch (RuntimeException e) {
                    return ResponseEntity.ok(new RawProduct(
                            "Flipkart is blocking automated requests",
                            "Unavailable — proxy needed",
                            "flipkart",
                            identity.getOriginalUrl()
                    ));
                }
            }
            default -> {
                return ResponseEntity.badRequest()
                        .body("Platform not supported yet: " + identity.getPlatform());
            }
        }
    }
}
