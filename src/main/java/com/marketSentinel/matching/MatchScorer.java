package com.marketSentinel.matching;

import com.marketSentinel.model.RawProduct;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scores how well a candidate product matches the source product.
 * Uses weighted attribute matching + Jaro-Winkler string similarity.
 */
@Component
public class MatchScorer {

    private final AttributeExtractor extractor;
    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();

    // Scoring weights
    private static final double W_BRAND = 0.35;
    private static final double W_MODEL = 0.25;
    private static final double W_SIZE  = 0.20;
    private static final double W_TITLE = 0.20;

    // Thresholds
    private static final double EXACT_THRESHOLD   = 0.80;
    private static final double SIMILAR_THRESHOLD  = 0.50;

    // Hard caps when decisive attributes clearly mismatch
    private static final double BRAND_MISMATCH_CAP = 0.30;
    private static final double SIZE_MISMATCH_CAP  = 0.50;

    public MatchScorer(AttributeExtractor extractor) {
        this.extractor = extractor;
    }

    // ─── Result record ───────────────────────────────────────────────────────

    public record MatchResult(double confidence, String matchType) {

        public static final String EXACT    = "exact";
        public static final String SIMILAR  = "similar";
        public static final String MISMATCH = "mismatch";
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public MatchResult score(RawProduct source, RawProduct candidate) {
        ProductAttributes srcAttr = extractor.extract(source.getTitle());
        ProductAttributes candAttr = extractor.extract(candidate.getTitle());

        double brandScore = scoreBrand(srcAttr.getBrand(), candAttr.getBrand());
        double modelScore = scoreModel(srcAttr.getModel(), candAttr.getModel());
        double sizeScore  = scoreSize(srcAttr, candAttr);
        double titleScore = scoreTitle(srcAttr.getNormalizedTitle(), candAttr.getNormalizedTitle());

        double confidence = (W_BRAND * brandScore)
                          + (W_MODEL * modelScore)
                          + (W_SIZE  * sizeScore)
                          + (W_TITLE * titleScore);

        // Hard overrides: brand mismatch is a strong disqualifier
        if (srcAttr.getBrand() != null && candAttr.getBrand() != null
                && !srcAttr.getBrand().equalsIgnoreCase(candAttr.getBrand())) {
            confidence = Math.min(confidence, BRAND_MISMATCH_CAP);
        }

        // Size/quantity mismatch: "500ml" vs "1l" — different product variant
        if (hasSizeMismatch(srcAttr, candAttr)) {
            confidence = Math.min(confidence, SIZE_MISMATCH_CAP);
        }

        String matchType;
        if (confidence >= EXACT_THRESHOLD) {
            matchType = MatchResult.EXACT;
        } else if (confidence >= SIMILAR_THRESHOLD) {
            matchType = MatchResult.SIMILAR;
        } else {
            matchType = MatchResult.MISMATCH;
        }

        return new MatchResult(Math.round(confidence * 100.0) / 100.0, matchType);
    }

    /**
     * Score and rank a list of candidates. Returns them sorted by confidence descending.
     */
    public List<ScoredProduct> scoreAll(RawProduct source, List<RawProduct> candidates) {
        return candidates.stream()
                .map(c -> new ScoredProduct(c, score(source, c)))
                .sorted((a, b) -> Double.compare(b.result().confidence(), a.result().confidence()))
                .toList();
    }

    public record ScoredProduct(RawProduct product, MatchResult result) {}

    // ─── Component scores ────────────────────────────────────────────────────

    private double scoreBrand(String srcBrand, String candBrand) {
        if (srcBrand == null || candBrand == null) return 0.5;  // can't determine, neutral
        return srcBrand.equalsIgnoreCase(candBrand) ? 1.0 : 0.0;
    }

    private double scoreModel(String srcModel, String candModel) {
        if (srcModel == null || candModel == null) return 0.5;
        return jaroWinkler.apply(srcModel.toLowerCase(), candModel.toLowerCase());
    }

    private double scoreSize(ProductAttributes src, ProductAttributes cand) {
        // Check storage first (electronics: 128GB vs 256GB)
        if (src.getStorage() != null && cand.getStorage() != null) {
            return src.getStorage().equalsIgnoreCase(cand.getStorage()) ? 1.0 : 0.0;
        }
        // Then check size/quantity (FMCG: 500ml vs 1L)
        if (src.getSizeOrQuantity() != null && cand.getSizeOrQuantity() != null) {
            return src.getSizeOrQuantity().equalsIgnoreCase(cand.getSizeOrQuantity()) ? 1.0 : 0.0;
        }
        // If one has it and the other doesn't — slight penalty
        if (src.getStorage() != null || cand.getStorage() != null
                || src.getSizeOrQuantity() != null || cand.getSizeOrQuantity() != null) {
            return 0.4;
        }
        return 0.5; // neither has size info — neutral
    }

    private double scoreTitle(String srcTitle, String candTitle) {
        if (srcTitle == null || candTitle == null) return 0.0;
        return jaroWinkler.apply(srcTitle.toLowerCase(), candTitle.toLowerCase());
    }

    private boolean hasSizeMismatch(ProductAttributes src, ProductAttributes cand) {
        // Storage mismatch
        if (src.getStorage() != null && cand.getStorage() != null
                && !src.getStorage().equalsIgnoreCase(cand.getStorage())) {
            return true;
        }
        // Quantity mismatch
        return src.getSizeOrQuantity() != null && cand.getSizeOrQuantity() != null
                && !src.getSizeOrQuantity().equalsIgnoreCase(cand.getSizeOrQuantity());
    }
}
