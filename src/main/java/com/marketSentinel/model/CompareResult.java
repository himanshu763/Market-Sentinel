package com.marketSentinel.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class CompareResult {
    private String               query;            // original URL pasted
    private List<PlatformResult> results;          // exact matches + source platform
    private String               status;           // "complete" | "partial"
    private List<PlatformResult> similarProducts;  // matches with matchType="similar"
}
