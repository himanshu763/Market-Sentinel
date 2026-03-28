package com.marketSentinel.matching;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductAttributes {
    private String brand;            // "Apple", "Samsung", "Cello"
    private String model;            // "iPhone 15", "Galaxy S24"
    private String storage;          // "128GB", "256GB"
    private String color;            // "Black", "Blue"
    private String sizeOrQuantity;   // "500ml", "35 Pieces", "1kg"
    private String category;         // "phone", "snack", "dinnerware"
    private String normalizedTitle;  // cleaned title used for extraction
}
