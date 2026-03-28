package com.marketSentinel.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketSentinel.model.RawProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ZeptoScraper {

    private static final Logger log = LoggerFactory.getLogger(ZeptoScraper.class);

    private static final String PLATFORM    = "zepto";
    private static final String BASE_URL    = "https://api.zepto.com";
    private static final String STORE_PATH  = "/api/v2/store/select/";
    private static final String SEARCH_PATH = "/api/v3/search/";
    private static final String PRODUCT_URL = "https://www.zepto.com/pn/%s/pvid/%s"; // name-slug / variant-id

    // Default: Delhi. Make configurable via @Value if needed.
    private static final String DEFAULT_LAT = "28.7041";
    private static final String DEFAULT_LON = "77.1025";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient   httpClient;
    private final ObjectMapper mapper;

    public ZeptoScraper() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    // ─── Public entry point ───────────────────────────────────────────────────

    public List<RawProduct> scrape(String query) {
        return scrape(query, DEFAULT_LAT, DEFAULT_LON);
    }

    public List<RawProduct> scrape(String query, String lat, String lon) {
        String storeId = resolveStoreId(lat, lon).orElse(null);
        if (storeId == null) {
            log.warn("Zepto: could not resolve store for ({}, {}), skipping", lat, lon);
            return List.of();
        }
        return fetchProducts(query, lat, lon, storeId);
    }

    // ─── Step 1: Resolve nearest store ID ────────────────────────────────────

    private Optional<String> resolveStoreId(String lat, String lon) {
        try {
            String url = BASE_URL + STORE_PATH + "?latitude=" + lat + "&longitude=" + lon;

            HttpRequest request = buildBaseRequest(url, lat, lon, "").build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Zepto store lookup returned HTTP {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root    = mapper.readTree(response.body());
            String   storeId = root.path("data").path("store").path("id").asText("").trim();

            if (storeId.isEmpty()) {
                log.warn("Zepto store lookup returned no store id");
                return Optional.empty();
            }

            log.info("Zepto resolved storeId={} for ({}, {})", storeId, lat, lon);
            return Optional.of(storeId);

        } catch (Exception e) {
            log.error("Zepto store lookup failed", e);
            return Optional.empty();
        }
    }

    // ─── Step 2: Search & map to RawProduct ──────────────────────────────────

    private List<RawProduct> fetchProducts(String query,
                                           String lat,
                                           String lon,
                                           String storeId) {
        List<RawProduct> results = new ArrayList<>();
        try {
            String encodedQuery = query.replace(" ", "%20");
            String url = BASE_URL + SEARCH_PATH
                    + "?query=" + encodedQuery
                    + "&page_number=0&page_size=20&store_id=" + storeId;

            HttpRequest request = buildBaseRequest(url, lat, lon, storeId)
                    .header("Referer", "https://www.zeptonow.com/search?query=" + encodedQuery)
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Zepto search '{}' → HTTP {}", query, response.statusCode());

            if (response.statusCode() != 200) {
                log.warn("Zepto non-200 body: {}",
                        response.body().substring(0, Math.min(300, response.body().length())));
                return results;
            }

            JsonNode root     = mapper.readTree(response.body());
            JsonNode sections = root.path("data").path("sections");

            if (!sections.isArray()) {
                log.warn("Zepto response missing 'data.sections' array");
                return results;
            }

            for (JsonNode section : sections) {
                JsonNode items = section.path("layout").path("items");
                if (!items.isArray()) continue;
                for (JsonNode item : items) {
                    mapToRawProduct(item).ifPresent(results::add);
                }
            }

            log.info("Zepto returned {} products for '{}'", results.size(), query);

        } catch (Exception e) {
            log.error("Zepto scrape failed for '{}'", query, e);
        }
        return results;
    }

    // ─── Mapping: JsonNode → RawProduct ──────────────────────────────────────

    private Optional<RawProduct> mapToRawProduct(JsonNode item) {
        try {
            JsonNode pv = item.path("productVariant");
            if (pv.isMissingNode()) return Optional.empty();

            String id   = pv.path("id").asText("").trim();
            // Product name lives under productVariant.product.name; fall back to pv.name
            String name = pv.path("product").path("name").asText("").trim();
            if (name.isEmpty()) name = pv.path("name").asText("").trim();

            if (name.isEmpty() || id.isEmpty()) return Optional.empty();

            String price      = formatPrice(pv);
            String productUrl = buildProductUrl(name, id);

            return Optional.of(new RawProduct(name, price, PLATFORM, productUrl));

        } catch (Exception e) {
            log.warn("Zepto: failed to map product node — {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Zepto stores prices in paise (1/100 of a rupee).
     * Formats as "₹33" or "₹33 (MRP: ₹40)" when a discount exists.
     */
    private String formatPrice(JsonNode pv) {
        long sellingPricePaise = pv.path("sellingPrice").asLong(0);
        long mrpPaise          = pv.path("mrp").asLong(0);

        if (sellingPricePaise <= 0) return "N/A";

        String formatted = "₹" + sellingPricePaise / 100;

        if (mrpPaise > sellingPricePaise) {
            formatted += " (MRP: ₹" + mrpPaise / 100 + ")";
        }

        return formatted;
    }

    /**
     * Zepto product URLs follow the pattern:
     * https://www.zeptonow.com/pn/{name-slug}/pvid/{variant-id}
     */
    private String buildProductUrl(String name, String id) {
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return String.format(PRODUCT_URL, slug, id);
    }

    /**
     * Centralises all required Zepto headers.
     * Returns a builder so callers can add extra headers (e.g. Referer).
     */
    private HttpRequest.Builder buildBaseRequest(String url,
                                                 String lat,
                                                 String lon,
                                                 String storeId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("User-Agent",  USER_AGENT)
                .header("Accept",      "application/json, text/plain, */*")
                .header("x-latitude",  lat)
                .header("x-longitude", lon)
                .header("appversion",  "1.0.0")
                .header("platform",    "web");

        if (!storeId.isEmpty()) {
            builder = builder.header("x-store-id", storeId);
        }

        return builder;
    }
}
