package com.marketSentinel.scraper;

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
public class FlipkartScraper {

    private static final Logger log = LoggerFactory.getLogger(FlipkartScraper.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private static final int MAX_RESULTS = 10;

    private final HttpClient httpClient;

    public FlipkartScraper() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ─── Direct product page scrape (by SKU + URL) ───────────────────────────

    public RawProduct scrape(String sku, String originalUrl) {
        try {
            Document doc = fetchDocument(originalUrl);

            String title = doc.select(".B_NuCI").text().trim();
            if (title.isEmpty()) title = doc.select("h1.yhB1nd").text().trim();
            if (title.isEmpty()) title = doc.select("span.B_NuCI").text().trim();
            if (title.isEmpty()) title = doc.select("h1 span").text().trim();

            Element priceElement = doc.select("._30jeq3._16Jk6d").first();
            if (priceElement == null) priceElement = doc.select("div._30jeq3").first();
            String price = (priceElement != null)
                    ? priceElement.text().trim()
                    : "Price not found";

            if (title.isEmpty()) {
                throw new RuntimeException("Could not parse Flipkart page — blocked or selectors changed");
            }

            return new RawProduct(title, price, "flipkart", originalUrl);

        } catch (Exception e) {
            throw new RuntimeException("Failed to scrape Flipkart: " + e.getMessage());
        }
    }

    // ─── Search results scrape (by query) ────────────────────────────────────

    public List<RawProduct> search(String query) {
        List<RawProduct> results = new ArrayList<>();
        try {
            // Step 1: Establish session by hitting homepage
            fetchHtml("https://www.flipkart.com");

            // Step 2: Search with session cookies (auto-managed by CookieManager)
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.flipkart.com/search?q=" + encoded
                    + "&otracker=search&otracker1=search&marketplace=FLIPKART&as-show=on&as=off";

            String html = fetchHtml(url);
            Document doc = Jsoup.parse(html);

            // Strategy 1: div[data-id] product cards
            Elements cards = doc.select("div[data-id]");

            for (Element card : cards) {
                if (results.size() >= MAX_RESULTS) break;

                String title = "";
                Element titleLink = card.select("a[title]").first();
                if (titleLink != null) title = titleLink.attr("title");

                if (title.isEmpty()) {
                    Element titleEl = card.select("a.wjcEIp, a._4rR01T, a.s1Q9rs, div.KzDlHZ").first();
                    if (titleEl != null) title = titleEl.text().trim();
                }
                if (title.isEmpty()) {
                    for (Element a : card.select("a[href*='/p/']")) {
                        String text = a.text().trim();
                        if (text.length() > 10) {
                            title = text;
                            titleLink = a;
                            break;
                        }
                    }
                }
                if (title.isEmpty()) continue;

                Element priceEl = card.select("div._30jeq3, div.Nx9bqj").first();
                String price = priceEl != null ? priceEl.text().trim() : "Price not found";

                String link = "";
                if (titleLink != null) link = titleLink.attr("href");
                if (link.isEmpty()) {
                    Element anyLink = card.select("a[href*='/p/']").first();
                    if (anyLink != null) link = anyLink.attr("href");
                }
                if (!link.startsWith("http")) link = "https://www.flipkart.com" + link;

                results.add(new RawProduct(title, price, "flipkart", link));
            }

            // Strategy 2: link-based extraction fallback
            if (results.isEmpty()) {
                Elements productLinks = doc.select("a[href*='/p/itm']");
                for (Element link : productLinks) {
                    if (results.size() >= MAX_RESULTS) break;

                    String title = link.attr("title");
                    if (title.isEmpty()) title = link.text().trim();
                    if (title.isEmpty() || title.length() < 10) continue;

                    String href = link.attr("href");
                    if (!href.startsWith("http")) href = "https://www.flipkart.com" + href;

                    Element parent = link.parent();
                    Element priceEl = parent != null
                            ? parent.select("div._30jeq3, div.Nx9bqj").first() : null;
                    String price = priceEl != null ? priceEl.text().trim() : "Price not found";

                    results.add(new RawProduct(title, price, "flipkart", href));
                }
            }

            log.info("Flipkart search '{}' → {} results", query, results.size());

        } catch (Exception e) {
            log.error("Flipkart search failed for '{}'", query, e);
        }
        return results;
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
        log.debug("Flipkart {} → HTTP {}", url.substring(0, Math.min(80, url.length())), response.statusCode());

        if (response.statusCode() == 403 || response.statusCode() == 429) {
            log.warn("Flipkart returned {} for {}", response.statusCode(), url);
        }

        return response.body();
    }

    private Document fetchDocument(String url) throws Exception {
        return Jsoup.parse(fetchHtml(url));
    }
}
