package io.openfednow.processing.fraud;

/**
 * Outcome of a {@link FraudScreeningPort#screen} call.
 *
 * <p>Three terminal outcomes:
 *
 * <ul>
 *   <li>{@link Decision#PASS} — payment proceeds normally. {@code reasonCode}
 *       and {@code description} are null.</li>
 *   <li>{@link Decision#REVIEW} — payment proceeds but is flagged for
 *       operational review. {@code reasonCode} indicates which rule
 *       suggested escalation; the framework currently logs and counts
 *       REVIEW outcomes but does not block on them.</li>
 *   <li>{@link Decision#BLOCK} — payment must be rejected before any
 *       side effects. {@code reasonCode} carries the ISO 20022 reason
 *       code returned in the resulting pacs.002 RJCT (typically
 *       {@code FRAD}).</li>
 * </ul>
 *
 * @param decision        the final outcome
 * @param reasonCode      ISO 20022 reason code (typically {@code FRAD} for BLOCK,
 *                        a free-form short code for REVIEW); null on PASS
 * @param description     human-readable explanation suitable for logs and
 *                        operator review; null on PASS
 */
public record ScreeningResult(Decision decision, String reasonCode, String description) {

    /** Discrete outcome of a fraud-screening evaluation. */
    public enum Decision {
        /** Payment may proceed; no fraud indicator detected. */
        PASS,
        /** Payment may proceed but is flagged for manual review. */
        REVIEW,
        /** Payment must be rejected; do not invoke any side effects. */
        BLOCK
    }

    /** Convenience factory for the most common case. */
    public static ScreeningResult pass() {
        return new ScreeningResult(Decision.PASS, null, null);
    }

    public static ScreeningResult block(String reasonCode, String description) {
        return new ScreeningResult(Decision.BLOCK, reasonCode, description);
    }

    public static ScreeningResult review(String reasonCode, String description) {
        return new ScreeningResult(Decision.REVIEW, reasonCode, description);
    }
}
