package com.marketSentinel.matching;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AttributeExtractor {

    // ─── Known brands (lowercase) ────────────────────────────────────────────

    private static final Set<String> KNOWN_BRANDS = Set.of(
            // Electronics
            "apple", "samsung", "oneplus", "xiaomi", "redmi", "poco", "realme",
            "oppo", "vivo", "motorola", "nokia", "boat", "boult", "noise",
            "sony", "lg", "hp", "dell", "lenovo", "asus", "acer",
            // FMCG / Grocery
            "amul", "tata", "parle", "haldiram", "britannia", "nestle",
            "maggi", "cadbury", "bisleri", "pepsi", "coca-cola", "dabur",
            "fortune", "aashirvaad", "mtr", "saffola", "sundrop",
            "chheda", "chhedas", "bikano", "kurkure", "lays",
            // Personal care / Beauty
            "nivea", "lakme", "loreal", "garnier", "dove", "himalaya",
            "mamaearth", "wow", "cetaphil", "neutrogena", "maybelline",
            "nykaa", "biotique", "colgate", "dettol", "savlon",
            // Home / Kitchen
            "cello", "borosil", "milton", "prestige", "pigeon", "bajaj",
            "havells", "philips", "crompton"
    );

    // ─── Regex patterns ──────────────────────────────────────────────────────

    private static final Pattern STORAGE_PATTERN =
            Pattern.compile("(\\d+)\\s*(gb|tb)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ml|l|litre|liter|g|gm|gram|kg|pieces?|pcs?|pack|count|units?|sheets?)",
                    Pattern.CASE_INSENSITIVE);

    private static final Set<String> KNOWN_COLORS = Set.of(
            "black", "white", "blue", "red", "green", "gold", "silver",
            "pink", "grey", "gray", "purple", "midnight", "starlight",
            "natural", "cream", "titanium", "yellow", "orange", "brown",
            "coral", "lavender", "mint", "ivory"
    );

    // ─── Category keywords ───────────────────────────────────────────────────

    private static final String[][] CATEGORY_KEYWORDS = {
            {"phone", "phone", "mobile", "smartphone", "iphone", "galaxy"},
            {"laptop", "laptop", "notebook", "macbook", "chromebook"},
            {"earbuds", "earbuds", "earphone", "headphone", "headset", "airpods", "buds"},
            {"snack", "chips", "namkeen", "biscuit", "cookie", "snack", "mixture", "bhujia"},
            {"beverage", "juice", "drink", "water", "soda", "cola", "tea", "coffee"},
            {"dairy", "milk", "curd", "paneer", "ghee", "butter", "cheese", "yogurt"},
            {"personal care", "shampoo", "conditioner", "serum", "cream", "moisturizer",
                    "sunscreen", "lotion", "facewash", "bodywash"},
            {"dinnerware", "dinner set", "plate", "bowl", "crockery", "cookware"},
            {"detergent", "detergent", "washing", "dishwash", "cleaner", "surf"},
    };

    // ─── Public API ──────────────────────────────────────────────────────────

    public ProductAttributes extract(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return ProductAttributes.builder().build();
        }

        String normalized = rawTitle
                .replaceAll("\\s+", " ")
                .trim();
        String lower = normalized.toLowerCase();

        String brand          = extractBrand(lower);
        String storage        = extractStorage(lower);
        String sizeOrQuantity = extractSize(lower);
        String color          = extractColor(lower);
        String category       = extractCategory(lower);
        String model          = extractModel(normalized, lower, brand, storage, sizeOrQuantity, color);

        return ProductAttributes.builder()
                .brand(brand)
                .model(model)
                .storage(storage)
                .color(color)
                .sizeOrQuantity(sizeOrQuantity)
                .category(category)
                .normalizedTitle(normalized)
                .build();
    }

    // ─── Extraction helpers ──────────────────────────────────────────────────

    private String extractBrand(String lower) {
        String[] words = lower.split("\\s+");
        // Check first word
        if (words.length > 0 && KNOWN_BRANDS.contains(words[0])) {
            return words[0];
        }
        // Check first two words combined (e.g., "coca-cola")
        if (words.length > 1) {
            String twoWord = words[0] + "-" + words[1];
            if (KNOWN_BRANDS.contains(twoWord)) {
                return twoWord;
            }
        }
        // Scan remaining words — brand might not be first on Blinkit/Zepto
        for (String word : words) {
            if (KNOWN_BRANDS.contains(word)) {
                return word;
            }
        }
        return null;
    }

    private String extractStorage(String lower) {
        Matcher m = STORAGE_PATTERN.matcher(lower);
        return m.find() ? m.group().replaceAll("\\s+", "").toUpperCase() : null;
    }

    private String extractSize(String lower) {
        Matcher m = SIZE_PATTERN.matcher(lower);
        return m.find() ? m.group().replaceAll("\\s+", "").toLowerCase() : null;
    }

    private String extractColor(String lower) {
        // Check for multi-word colors first
        for (String color : KNOWN_COLORS) {
            if (lower.contains(color)) {
                return color;
            }
        }
        return null;
    }

    private String extractCategory(String lower) {
        for (String[] group : CATEGORY_KEYWORDS) {
            String categoryName = group[0];
            for (int i = 1; i < group.length; i++) {
                if (lower.contains(group[i])) {
                    return categoryName;
                }
            }
        }
        return null;
    }

    /**
     * Model = what remains after stripping brand, storage, size, color, and noise.
     * Uses the original-case title but removes tokens identified in the lowercase version.
     */
    private String extractModel(String original, String lower,
                                String brand, String storage,
                                String sizeOrQuantity, String color) {
        String working = original;

        // Remove brand (case-insensitive)
        if (brand != null) {
            working = working.replaceFirst("(?i)\\b" + Pattern.quote(brand) + "\\b", "");
        }
        // Remove storage
        if (storage != null) {
            working = working.replaceFirst("(?i)" + Pattern.quote(storage), "");
            // Also remove spaced version like "128 GB"
            working = working.replaceFirst("(?i)\\d+\\s*(gb|tb)", "");
        }
        // Remove size
        if (sizeOrQuantity != null) {
            working = working.replaceFirst("(?i)" + Pattern.quote(sizeOrQuantity), "");
            working = working.replaceFirst(
                    "(?i)\\d+(?:\\.\\d+)?\\s*(ml|l|litre|liter|g|gm|gram|kg|pieces?|pcs?|pack|count|units?|sheets?)", "");
        }
        // Remove color
        if (color != null) {
            working = working.replaceFirst("(?i)\\b" + Pattern.quote(color) + "\\b", "");
        }
        // Remove common noise
        working = working.replaceAll("(?i)\\b(with|for|and|the|new|latest|edition|series|version)\\b", "");
        // Remove punctuation and collapse spaces
        working = working.replaceAll("[^a-zA-Z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (working.isEmpty()) return null;

        // Cap at first 5 significant words
        String[] words = working.split("\\s+");
        int limit = Math.min(words.length, 5);
        return String.join(" ", java.util.Arrays.copyOf(words, limit));
    }
}
