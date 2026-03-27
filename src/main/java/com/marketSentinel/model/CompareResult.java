package com.marketSentinel.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class CompareResult {
    private String query;           // the original URL pasted
    private List<RawProduct> results;  // one entry per platform
    private String status;          // "complete" or "partial"
}