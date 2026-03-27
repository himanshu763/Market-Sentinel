package com.marketSentinel.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketSentinel.model.RawProduct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class CacheService {

    // StringRedisTemplate is Spring's built-in Redis client
    // It stores everything as plain strings
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;  // converts objects to/from JSON strings

    public CacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // Try to get a cached product
    // Returns empty if nothing is cached
    public Optional<RawProduct> get(String cacheKey) {
        try {
            String json = redis.opsForValue().get("cache:" + cacheKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, RawProduct.class));
        } catch (Exception e) {
            // If Redis is down or JSON is corrupt, just return empty
            // Never crash the app because of cache failure
            return Optional.empty();
        }
    }

    // Save a product to cache with a TTL (time to live)
    public void put(String cacheKey, RawProduct product, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(product);
            redis.opsForValue().set("cache:" + cacheKey, json, ttl);
        } catch (Exception e) {
            // If Redis is down, just skip caching silently
            System.out.println("Cache write failed: " + e.getMessage());
        }
    }
}