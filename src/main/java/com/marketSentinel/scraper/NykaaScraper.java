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

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class NykaaScraper {

    private static final Logger log = LoggerFactory.getLogger(NykaaScraper.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private static final int MAX_RESULTS = 10;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public NykaaScraper() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ─── Direct product page scrape (by ID + URL) ────────────────────────────

    public RawProduct scrape(String productId, String originalUrl) {
        try {
            Document doc = fetchDocument(originalUrl);

            // 1. Try JSON-LD structured data
            RawProduct fromJsonLd = tryJsonLd(doc, originalUrl);
            if (fromJsonLd != null) return fromJsonLd;

            // 2. CSS selector fallback
            String title = doc.select("h1.css-titletext").text().trim();
            if (title.isEmpty()) title = doc.select("h1[data-testid=product-title]").text().trim();
            if (title.isEmpty()) {
                Element h1 = doc.select("h1").first();
                title = h1 != null ? h1.text().trim() : "";
            }

            Element priceEl = doc.select("span.css-pricetext").first();
            if (priceEl == null) priceEl = doc.select("span[data-testid=price]").first();
            String price = priceEl != null ? priceEl.text().trim() : "Price not found";

            if (title.isEmpty()) {
                throw new RuntimeException("Could not parse Nykaa page — likely JS-rendered or blocked");
            }

            return new RawProduct(title, price, "nykaa", originalUrl);

        } catch (Exception e) {
            throw new RuntimeException("Failed to scrape Nykaa: " + e.getMessage());
        }
    }

    // ─── Search results scrape (by query) ────────────────────────────────────

    public List<RawProduct> search(String query) {
        List<RawProduct> results = new ArrayList<>();
        try {
            // Step 1: Establish session
            fetchHtml("https://www.nykaa.com");

            // Step 2: Search with session cookies (auto-managed by CookieManager)
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.nykaa.com/search/result/?q=" + encoded
                    + "&root=search&searchType=Manual";

            String html = fetchHtml(url);
            Document doc = Jsoup.parse(html);

            // 1. Try embedded JSON: window.__PRELOADED_STATE__
            List<RawProduct> fromJson = tryEmbeddedJson(doc);
            if (!fromJson.isEmpty()) {
                log.info("Nykaa search '{}' → {} results (from embedded JSON)", query, fromJson.size());
                return fromJson;
            }

            // 2. Fallback: CSS selectors
            Elements cards = doc.select("div.productWrapper, div[data-product-id], div[class*=product-list] > div");

            for (Element card : cards) {
                if (results.size() >= MAX_RESULTS) break;

                Element titleEl = card.select("div[class*=product-name], div.css-xrzmfa, h3").first();
                if (titleEl == null) continue;
                String title = titleEl.text().trim();
                if (title.isEmpty()) continue;

                Element brandEl = card.select("div[class*=brand-name], div.css-brand").first();
                if (brandEl != null) {
                    String brand = brandEl.text().trim();
                    if (!brand.isEmpty() && !title.toLowerCase().startsWith(brand.toLowerCase())) {
                        title = brand + " " + title;
                    }
                }

                Element priceEl = card.select("span[class*=price], span.css-pricetext").first();
                String price = priceEl != null ? priceEl.text().trim() : "Price not found";

                Element linkEl = card.select("a[href]").first();
                String link = linkEl != null ? linkEl.attr("href") : url;
                if (!link.startsWith("http")) link = "https://www.nykaa.com" + link;

                results.add(new RawProduct(title, price, "nykaa", link));
            }

            log.info("Nykaa search '{}' → {} results", query, results.size());

        } catch (Exception e) {
            log.error("Nykaa search failed for '{}'", query, e);
        }
        return results;
    }

    // ─── JSON extraction helpers ─────────────────────────────────────────────

    private List<RawProduct> tryEmbeddedJson(Document doc) {
        List<RawProduct> results = new ArrayList<>();
        try {
            for (Element script : doc.select("script")) {
                String content = script.html();
                if (!content.contains("__PRELOADED_STATE__")) continue;

                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start < 0 || end <= start) continue;

                JsonNode root = mapper.readTree(content.substring(start, end + 1));

                JsonNode products = root.path("productListing").path("products");
                if (!products.isArray()) products = root.path("search").path("products");
                if (!products.isArray()) continue;

                for (JsonNode product : products) {
                    if (results.size() >= MAX_RESULTS) break;

                    String title = product.path("title").asText("").trim();
                    if (title.isEmpty()) title = product.path("name").asText("").trim();
                    if (title.isEmpty()) continue;

                    String brand = product.path("brandName").asText("").trim();
                    if (!brand.isEmpty() && !title.toLowerCase().startsWith(brand.toLowerCase())) {
                        title = brand + " " + title;
                    }

                    double priceVal = product.path("price").asDouble(0);
                    if (priceVal == 0) priceVal = product.path("offerPrice").asDouble(0);
                    String price = priceVal > 0 ? "₹" + (int) priceVal : "Price not found";

                    String slug = product.path("slug").asText("").trim();
                    String productId = product.path("id").asText("").trim();
                    String link = !slug.isEmpty()
                            ? "https://www.nykaa.com/" + slug + "/p/" + productId
                            : "https://www.nykaa.com";

                    results.add(new RawProduct(title, price, "nykaa", link));
                }
            }
        } catch (Exception e) {
            log.debug("Nykaa embedded JSON parse failed: {}", e.getMessage());
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
                JsonNode offerNode = offers.isArray() ? offers.get(0) : offers;
                String rawPrice = offerNode.path("price").asText("").trim();
                String price = rawPrice.isEmpty() ? "Price not found" : "₹" + rawPrice;

                log.info("Nykaa JSON-LD parsed: {}", name);
                return new RawProduct(name, price, "nykaa", originalUrl);

            } catch (Exception e) {
                log.debug("Nykaa JSON-LD parse failed for one script block: {}", e.getMessage());
            }
        }
        return null;
    }

    // ─── HTTP helpers using HttpClient (HTTP/2 + auto cookie management) ─────

    private String fetchHtml(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Nykaa {} → HTTP {}", url.substring(0, Math.min(80, url.length())), response.statusCode());

        if (response.statusCode() == 403 || response.statusCode() == 429) {
            log.warn("Nykaa returned {} for {}", response.statusCode(), url);
        }

        return response.body();
    }

    private Document fetchDocument(String url) throws Exception {
        return Jsoup.parse(fetchHtml(url));
    }
}
