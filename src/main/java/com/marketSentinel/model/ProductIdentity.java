package com.marketSentinel.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter                  // Lombok generates all getters automatically
@AllArgsConstructor      // Lombok generates constructor with all fields
public class ProductIdentity {
    private String platform;    // "amazon", "flipkart", "blinkit"
    private String productId;   // "B0CHX3QBCH"
    private String idType;      // "ASIN", "SKU", "ProductId"
    private String originalUrl;
}