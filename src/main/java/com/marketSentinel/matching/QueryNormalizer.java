package com.marketSentinel.matching;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Cleans raw product titles (especially Amazon's verbose ones) into
 * focused search queries suitable for Blinkit/Zepto search APIs.
 *
 * "Cello Opalware Dazzle Series Dinner Set, 35 Pieces | Opal Glass ... | Fulfilled by Amazon"
 *  → "Cello Opalware Dazzle Series Dinner Set 35 Pieces"
 */
@Component
public class QueryNormalizer {

    private static final int MAX_QUERY_WORDS = 8;

    // ─── Noise patterns (compiled once) ──────────────────────────────────────

    private static final Pattern PARENTHESIZED = Pattern.compile("\\([^)]*\\)");
    private static final Pattern AFTER_PIPE    = Pattern.compile("\\|.*");

    private static final Pattern AMAZON_NOISE = Pattern.compile(
            "(?i)(fulfilled\\s+by\\s+amazon|visit\\s+the\\s+\\S+\\s+store|" +
            "brand:\\s*\\S+|by\\s+amazon|amazon'?s?\\s+choice|" +
            "best\\s+seller|limited\\s+time\\s+deal|great\\s+indian\\s+festival|" +
            "lightning\\s+deal|deal\\s+of\\s+the\\s+day|" +
            "new\\s+launch|latest\\s+\\d+\\s+model)"
    );

    private static final Pattern FILLER_ADJECTIVES = Pattern.compile(
            "(?i)\\b(premium|exclusive|original|genuine|advanced|professional|" +
            "imported|superior|ultra|mega|super|extra|special|deluxe|" +
            "ideal\\s+for|suitable\\s+for|perfect\\s+for|designed\\s+for|" +
            "gift\\s+pack|combo\\s+pack|value\\s+pack|multipack)\\b"
    );

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-zA-Z0-9₹\\s]");
    private static final Pattern MULTI_SPACE  = Pattern.compile("\\s+");

    // ─── Public API ──────────────────────────────────────────────────────────

    public String normalize(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return rawTitle;
        }

        String q = rawTitle;

        // 1. Strip everything after pipe — Amazon titles pack noise there
        q = AFTER_PIPE.matcher(q).replaceAll("");

        // 2. Unwrap parentheses: "(128 GB)" → " 128 GB "
        q = PARENTHESIZED.matcher(q).replaceAll(m -> {
            String inner = m.group().replaceAll("[()]", "").trim();
            return inner.isEmpty() ? "" : " " + inner + " ";
        });

        // 3. Remove Amazon-specific noise phrases
        q = AMAZON_NOISE.matcher(q).replaceAll("");

        // 4. Remove filler adjectives that hurt search precision
        q = FILLER_ADJECTIVES.matcher(q).replaceAll("");

        // 5. Clean punctuation and collapse spaces
        q = NON_ALPHANUM.matcher(q).replaceAll(" ");
        q = MULTI_SPACE.matcher(q).replaceAll(" ").trim();

        // 6. Truncate to MAX_QUERY_WORDS — Blinkit/Zepto work best with short queries
        String[] words = q.split("\\s+");
        if (words.length > MAX_QUERY_WORDS) {
            q = String.join(" ", java.util.Arrays.copyOf(words, MAX_QUERY_WORDS));
        }

        return q;
    }
}
