package com.marketSentinel.scraper;

import com.marketSentinel.model.RawProduct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class AmazonScraper {

    public RawProduct scrape(String asin) {

        String url = "https://www.amazon.in/dp/" + asin;

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-IN,en;q=0.9")
                    .timeout(8000)
                    .get();

            // Extract title
            String title = doc.select("#productTitle").text().trim();

            // Extract price — Amazon splits it into whole + decimal
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
}