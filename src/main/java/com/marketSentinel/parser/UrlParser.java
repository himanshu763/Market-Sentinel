package com.marketSentinel.parser;

import com.marketSentinel.model.ProductIdentity;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class UrlParser {

    public ProductIdentity parse(String rawUrl) {

        // URI breaks the URL into parts
        // "https://www.amazon.in/Apple-iPhone/dp/B0CHX3QBCH?ref=sr"
        // host = "www.amazon.in"
        // path = "/Apple-iPhone/dp/B0CHX3QBCH"
        URI uri = URI.create(rawUrl);
        String host = uri.getHost();
        String path = uri.getPath();

        if (host == null) {
            throw new IllegalArgumentException("Invalid URL: " + rawUrl);
        }

        if (host.contains("amazon")) {
            return new ProductIdentity("amazon", extractAsin(path), "ASIN", rawUrl);
        }
        if (host.contains("flipkart")) {
            return new ProductIdentity("flipkart", extractFlipkartSku(path), "SKU", rawUrl);
        }
        if (host.contains("blinkit")) {
            return new ProductIdentity("blinkit", extractBlinkitId(path), "ProductId", rawUrl);
        }
        if (host.contains("zepto") || host.contains("zeptonow")) {
            return new ProductIdentity("zepto", extractZeptoPvid(path), "ProductVariantId", rawUrl);
        }
        if (host.contains("myntra")) {
            return new ProductIdentity("myntra", extractMyntraId(path), "ProductId", rawUrl);
        }
        if (host.contains("nykaa")) {
            return new ProductIdentity("nykaa", extractNykaaId(path), "ProductId", rawUrl);
        }

        throw new IllegalArgumentException("Unsupported platform: " + host);
    }

    // Amazon URL: /Apple-iPhone-15/dp/B0CHX3QBCH/ref=...
    // Split by "/" gives: ["", "Apple-iPhone-15", "dp", "B0CHX3QBCH", "ref=..."]
    // We want the segment right after "dp"
    private String extractAsin(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("dp") && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        throw new IllegalArgumentException("ASIN not found in: " + path);
    }

    // Flipkart URL: /apple-iphone-15/p/itm123abc
    // We want the segment right after "p"
    private String extractFlipkartSku(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("p") && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        throw new IllegalArgumentException("SKU not found in: " + path);
    }

    // Blinkit URL: /prn/iphone-15/prid/123456
    // We want the segment right after "prid"
    private String extractBlinkitId(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("prid") && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        throw new IllegalArgumentException("Product ID not found in: " + path);
    }

    // Zepto URL: /pn/apple-iphone-15/pvid/abc-uuid-123
    // We want the segment right after "pvid"
    private String extractZeptoPvid(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("pvid") && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        throw new IllegalArgumentException("Product variant ID not found in: " + path);
    }

    // Myntra URL: /{category}/{brand}/{product-name}/{id}/buy
    // The numeric ID is the segment immediately before "buy"
    private String extractMyntraId(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("buy") && i > 0) {
                return parts[i - 1];
            }
        }
        // Fallback: last purely numeric segment
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches("\\d+")) {
                return parts[i];
            }
        }
        throw new IllegalArgumentException("Product ID not found in Myntra path: " + path);
    }

    // Nykaa URL: /{product-slug}/p/{id}?skuId=...
    // We want the segment right after "p"
    private String extractNykaaId(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("p") && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        throw new IllegalArgumentException("Product ID not found in Nykaa path: " + path);
    }
}