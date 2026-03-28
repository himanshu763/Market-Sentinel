package com.marketSentinel.scraper;

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
public class AmazonScraper {

    private static final Logger log = LoggerFactory.getLogger(AmazonScraper.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private static final int MAX_RESULTS = 10;

    // ─── Direct product page scrape (by ASIN) ────────────────────────────────

    public RawProduct scrape(String asin) {

        String url = "https://www.amazon.in/dp/" + asin;

        try {
            Document doc = connect(url);

            String title = doc.select("#productTitle").text().trim();

            Element priceWhole = doc.select(".a-price-whole").first();
            String price = (priceWhole != null)
                    ? "₹" + priceWhole.text().trim()
                    : "Price not found";

            if (title.isEmpty()) {
                throw new RuntimeException("Could not parse page — Amazon may have blocked this request");
            }

            return new RawProduct(title, price, "amazon", url);

        } catch (Exception e) {
            throw new RuntimeException("Failed to scrape Amazon: " + e.getMessage());
        }
    }

    // ─── Search results scrape (by query) ────────────────────────────────────

    public List<RawProduct> search(String query) {
        List<RawProduct> results = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.amazon.in/s?k=" + encoded;

            Document doc = connect(url);

            // Amazon search results: each product card is a div with data-component-type="s-search-result"
            Elements cards = doc.select("div[data-component-type=s-search-result]");

            for (Element card : cards) {
                if (results.size() >= MAX_RESULTS) break;

                // Title: inside h2 > a > span
                Element titleEl = card.select("h2 a span").first();
                if (titleEl == null) continue;
                String title = titleEl.text().trim();
                if (title.isEmpty()) continue;

                // Price: span.a-price-whole
                Element priceEl = card.select("span.a-price-whole").first();
                String price = priceEl != null ? "₹" + priceEl.text().trim() : "Price not found";

                // Link: h2 > a href
                Element linkEl = card.select("h2 a").first();
                String link = linkEl != null
                        ? "https://www.amazon.in" + linkEl.attr("href")
                        : "https://www.amazon.in/s?k=" + encoded;

                results.add(new RawProduct(title, price, "amazon", link));
            }

            log.info("Amazon search '{}' → {} results", query, results.size());

        } catch (Exception e) {
            log.error("Amazon search failed for '{}'", query, e);
        }
        return results;
    }

    // ─── Shared connection helper ────────────────────────────────────────────

    private Document connect(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Encoding", "gzip, deflate, br")
                .referrer("https://www.google.com")
                .timeout(8000)
                .get();
    }
}
