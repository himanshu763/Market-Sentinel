package com.marketSentinel.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PlatformResult {

    private String     platform;    // "amazon" | "blinkit" | "zepto" | "myntra" | "nykaa"
    private RawProduct product;     // null when status != "success"
    private String     status;      // "success" | "timeout" | "error"
    private String     message;     // null on success; reason on timeout/error
    private Double     confidence;  // 0.0–1.0 match confidence; null for source platform
    private String     matchType;   // "exact" | "similar" | "mismatch" | null

    // ─── Factory methods ──────────────────────────────────────────────────────

    /** Source platform or single-result scrape (no scoring needed). */
    public static PlatformResult success(String platform, RawProduct product) {
        return new PlatformResult(platform, product, "success", null, null, null);
    }

    /** Cross-platform match with confidence score. */
    public static PlatformResult success(String platform, RawProduct product,
                                         double confidence, String matchType) {
        return new PlatformResult(platform, product, "success", null, confidence, matchType);
    }

    public static PlatformResult timeout(String platform) {
        return new PlatformResult(platform, null, "timeout",
                platform + " result unavailable. Try refreshing for live price.",
                null, null);
    }

    public static PlatformResult error(String platform, String message) {
        return new PlatformResult(platform, null, "error", message, null, null);
    }
}
