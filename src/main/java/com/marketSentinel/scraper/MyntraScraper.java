package com.marketSentinel.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketSentinel.model.RawProduct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class MyntraScraper {

    private static final Logger log = LoggerFactory.getLogger(MyntraScraper.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private static final int MAX_RESULTS = 10;

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Direct product page scrape (by ID + URL) ────────────────────────────

    public RawProduct scrape(String productId, String originalUrl) {
        try {
            Document doc = connect(originalUrl);

            // 1. Try JSON-LD structured data (SEO-injected, survives SSR)
            RawProduct fromJsonLd = tryJsonLd(doc, originalUrl);
            if (fromJsonLd != null) return fromJsonLd;

            // 2. CSS selector fallback
            String title = doc.select("h1.pdp-title").text().trim();
            if (title.isEmpty()) title = doc.select("h1.pdp-name").text().trim();

            Element priceEl = doc.select("span.pdp-price strong").first();
            if (priceEl == null) priceEl = doc.select("div.pdp-price").first();
            String price = priceEl != null ? priceEl.text().trim() : "Price not found";

            if (title.isEmpty()) {
                throw new RuntimeException("Could not parse Myntra page — likely JS-rendered or blocked");
            }

            return new RawProduct(title, price, "myntra", originalUrl);

        } catch (Exception e) {
            throw new RuntimeException("Failed to scrape Myntra: " + e.getMessage());
        }
    }

    // ─── Search results scrape (by query) ────────────────────────────────────

    public List<RawProduct> search(String query) {
        List<RawProduct> results = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.myntra.com/" + query.toLowerCase().replaceAll("[^a-z0-9]+", "-");

            Document doc = connect(url);

            // 1. Try embedded JSON: Myntra injects search data in window.__myx
            List<RawProduct> fromJson = tryEmbeddedJson(doc);
            if (!fromJson.isEmpty()) {
                log.info("Myntra search '{}' → {} results (from embedded JSON)", query, fromJson.size());
                return fromJson;
            }

            // 2. Fallback: CSS selectors for product listing cards
            Elements products = doc.select("li.product-base");

            for (Element product : products) {
                if (results.size() >= MAX_RESULTS) break;

                String brand = product.select(".product-brand").text().trim();
                String name  = product.select(".product-product").text().trim();
                if (name.isEmpty()) continue;

                String title = brand.isEmpty() ? name : brand + " " + name;

                // Price: try discounted price first, then original
                Element priceEl = product.select(".product-discountedPrice").first();
                if (priceEl == null) priceEl = product.select(".product-price span").first();
                String price = priceEl != null ? priceEl.text().trim() : "Price not found";

                // Link
                Element linkEl = product.select("a[href]").first();
                String link = linkEl != null
                        ? "https://www.myntra.com/" + linkEl.attr("href")
                        : url;

                results.add(new RawProduct(title, price, "myntra", link));
            }

            log.info("Myntra search '{}' → {} results", query, results.size());

        } catch (Exception e) {
            log.error("Myntra search failed for '{}'", query, e);
        }
        return results;
    }

    // ─── JSON extraction helpers ─────────────────────────────────────────────

    private List<RawProduct> tryEmbeddedJson(Document doc) {
        List<RawProduct> results = new ArrayList<>();
        try {
            for (Element script : doc.select("script")) {
                String content = script.html();
                if (!content.contains("window.__myx")) continue;

                // Extract JSON: window.__myx = {...};
                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start < 0 || end <= start) continue;

                JsonNode root = mapper.readTree(content.substring(start, end + 1));
                JsonNode searchData = root.path("searchData").path("results").path("products");

                if (!searchData.isArray()) continue;

                for (JsonNode product : searchData) {
                    if (results.size() >= MAX_RESULTS) break;

                    String brand = product.path("brand").asText("").trim();
                    String name  = product.path("product").asText("").trim();
                    if (name.isEmpty()) continue;

                    String title = brand.isEmpty() ? name : brand + " " + name;

                    int priceVal = product.path("price").asInt(0);
                    String price = priceVal > 0 ? "₹" + priceVal : "Price not found";

                    int productId = product.path("productId").asInt(0);
                    String link = productId > 0
                            ? "https://www.myntra.com/" + productId
                            : "https://www.myntra.com";

                    results.add(new RawProduct(title, price, "myntra", link));
                }
            }
        } catch (Exception e) {
            log.debug("Myntra embedded JSON parse failed: {}", e.getMessage());
        }
        return results;
    }

    private RawProduct tryJsonLd(Document doc, String originalUrl) {
        Elements scripts = doc.select("script[type=application/ld+json]");
        for (Element script : scripts) {
            try {
                JsonNode json = mapper.readTree(script.html());
                if (!"Product".equals(json.path("@type").asText())) continue;

                String name = json.path("name").asText("").trim();
                if (name.isEmpty()) continue;

                JsonNode offers = json.path("offers");
                String rawPrice = offers.path("price").asText("").trim();
                String price = rawPrice.isEmpty() ? "Price not found" : "₹" + rawPrice;

                log.info("Myntra JSON-LD parsed: {}", name);
                return new RawProduct(name, price, "myntra", originalUrl);

            } catch (Exception e) {
                log.debug("Myntra JSON-LD parse failed for one script block: {}", e.getMessage());
            }
        }
        return null;
    }

    // ─── Shared connection helper ────────────────────────────────────────────

    private Document connect(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .referrer("https://www.google.com")
                .timeout(8000)
                .get();
    }
}
