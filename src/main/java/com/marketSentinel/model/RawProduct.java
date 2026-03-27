package com.marketSentinel.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RawProduct {
    private String title;
    private String price;
    private String platform;
    private String productUrl;
}