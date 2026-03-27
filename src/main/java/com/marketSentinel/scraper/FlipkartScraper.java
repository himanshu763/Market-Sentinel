package com.marketSentinel.scraper;

import com.marketSentinel.model.RawProduct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class FlipkartScraper {

    // Takes the full URL now, not just the SKU
    public RawProduct scrape(String sku, String originalUrl) {

        try {
            Document doc = Jsoup.connect(originalUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-IN,en;q=0.9")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Cache-Control", "max-age=0")
                    .referrer("https://www.google.com")  // pretend we came from Google
                    .timeout(8000)
                    .get();

            // Try multiple selectors — Flipkart changes them often
            String title = doc.select(".B_NuCI").text().trim();
            if (title.isEmpty()) title = doc.select("h1.yhB1nd").text().trim();
            if (title.isEmpty()) title = doc.select("span.B_NuCI").text().trim();

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
}