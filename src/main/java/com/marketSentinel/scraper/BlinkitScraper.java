package com.marketSentinel.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketSentinel.model.RawProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class BlinkitScraper {

    private static final Logger log = LoggerFactory.getLogger(BlinkitScraper.class);

    private static final String PLATFORM    = "blinkit";
    private static final String BASE_URL    = "https://blinkit.com";
    private static final String SEARCH_PATH = "/v1/layout/search";
    private static final String PRODUCT_URL = "https://blinkit.com/prn/%s/prid/%s";

    private static final String DEFAULT_LAT = "28.7041";
    private static final String DEFAULT_LON = "77.1025";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/146.0.0.0 Safari/537.36";

    private static final String AUTH_KEY        = "c761ec3633c22afad934fb17a66385c1c06c5472b4898b866b7306186d0bb477";
    private static final String APP_VERSION     = "1010101010";
    private static final String RN_BUNDLE_VER   = "1009003012";
    private static final String WEB_APP_VERSION = "1008010016";
    private static final String DEVICE_ID       = "bf502da11b157ba7";

    private final HttpClient   httpClient;
    private final ObjectMapper mapper;

    public BlinkitScraper() {
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
        return fetchProducts(query, lat, lon);
    }

    // ─── Search & map to RawProduct ──────────────────────────────────────────

    private List<RawProduct> fetchProducts(String query, String lat, String lon) {
        List<RawProduct> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = BASE_URL + SEARCH_PATH
                    + "?q=" + encodedQuery
                    + "&search_type=type_to_search";

            HttpRequest request = buildBaseRequest(url, lat, lon)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Referer", "https://blinkit.com/s/?q=" + encodedQuery)
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Blinkit search '{}' → HTTP {}", query, response.statusCode());

            if (response.statusCode() != 200) {
                log.warn("Blinkit non-200 body: {}",
                        response.body().substring(0, Math.min(300, response.body().length())));
                return results;
            }

            JsonNode root = mapper.readTree(response.body());

            // Response: { "response": { "snippets": [ { "data": { ... } } ] } }
            JsonNode snippets = root.path("response").path("snippets");

            if (!snippets.isArray() || snippets.isEmpty()) {
                log.warn("Blinkit: no snippets in response. Body snippet: {}",
                        response.body().substring(0, Math.min(500, response.body().length())));
                return results;
            }

            for (JsonNode snippet : snippets) {
                mapToRawProduct(snippet.path("data")).ifPresent(results::add);
            }

            log.info("Blinkit returned {} products for '{}'", results.size(), query);

        } catch (Exception e) {
            log.error("Blinkit scrape failed for '{}'", query, e);
        }
        return results;
    }

    // ─── Mapping: snippet.data → RawProduct ──────────────────────────────────

    private Optional<RawProduct> mapToRawProduct(JsonNode data) {
        try {
            // Product snippets have identity.id and name.text
            String id   = data.path("identity").path("id").asText("").trim();
            String name = data.path("name").path("text").asText("").trim();

            // Skip non-product snippets (headers, banners, etc.)
            if (name.isEmpty() || id.isEmpty() || id.equals("product_container")) {
                return Optional.empty();
            }

            String unit = data.path("variant").path("text").asText("").trim();
            String title = unit.isEmpty() ? name : name + " — " + unit;

            String price      = formatPrice(data);
            String productUrl = buildProductUrl(name, id);

            return Optional.of(new RawProduct(title, price, PLATFORM, productUrl));

        } catch (Exception e) {
            log.warn("Blinkit: failed to map snippet — {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Prices come as text: normal_price.text = "₹74", mrp.text = "₹94"
     */
    private String formatPrice(JsonNode data) {
        String priceText = data.path("normal_price").path("text").asText("").trim();
        String mrpText   = data.path("mrp").path("text").asText("").trim();

        if (priceText.isEmpty()) return "N/A";

        // If MRP has strikethrough (is_markdown = 1), show both
        int isMarkdown = data.path("mrp").path("is_markdown").asInt(0);
        if (isMarkdown == 1 && !mrpText.isEmpty() && !mrpText.equals(priceText)) {
            return priceText + " (MRP: " + mrpText + ")";
        }

        return priceText;
    }

    private String buildProductUrl(String name, String id) {
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return String.format(PRODUCT_URL, slug, id);
    }

    private HttpRequest.Builder buildBaseRequest(String url, String lat, String lon) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent",         USER_AGENT)
                .header("Accept",             "*/*")
                .header("Content-Type",       "application/json")
                .header("lat",                lat)
                .header("lon",                lon)
                .header("auth_key",           AUTH_KEY)
                .header("app_client",         "consumer_web")
                .header("app_version",        APP_VERSION)
                .header("device_id",          DEVICE_ID)
                .header("platform",           "mobile_web")
                .header("rn_bundle_version",  RN_BUNDLE_VER)
                .header("web_app_version",    WEB_APP_VERSION)
                .header("session_uuid",       UUID.randomUUID().toString())
                .header("access_token",       "null");
    }
}
