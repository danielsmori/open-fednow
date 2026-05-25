package io.openfednow.processing.fraud;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the rule layer of {@link DefaultFraudScreeningService} — issue #47.
 *
 * <p>Mocks {@link StringRedisTemplate} so the denylist, amount-cap, and review rules
 * can be exercised without Redis. Velocity has its own integration coverage —
 * see {@link DefaultFraudScreeningIntegrationTest}.
 */
class DefaultFraudScreeningServiceTest {

    // ── Construction validation ──────────────────────────────────────────────

    @Test
    void zeroOrNegativeMaxAmountIsRejected() {
        assertThatThrownBy(() -> newService(BigDecimal.ZERO, 10, 60, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-single-transfer-amount");
        assertThatThrownBy(() -> newService(new BigDecimal("-1"), 10, 60, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOrNegativeVelocityIsRejected() {
        assertThatThrownBy(() -> newService(new BigDecimal("1000"), 0, 60, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-per-window");
        assertThatThrownBy(() -> newService(new BigDecimal("1000"), 10, 0, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-seconds");
    }

    @Test
    void denylistIsParsedFromCsv() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("1000"), 10, 60, " ACC-A , ACC-B ,ACC-C,, ");

        assertThat(service.getDenylist()).containsExactlyInAnyOrder("ACC-A", "ACC-B", "ACC-C");
    }

    @Test
    void emptyDenylistPropertyIsHandled() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("1000"), 10, 60, "");

        assertThat(service.getDenylist()).isEmpty();
    }

    // ── Denylist (highest priority) ──────────────────────────────────────────

    @Test
    void debtorOnDenylistIsBlocked() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("100000"), 1000, 60, "BAD-DEBTOR");

        ScreeningResult result = service.screen(payment("BAD-DEBTOR", "ACC-OK", new BigDecimal("10.00")));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.BLOCK);
        assertThat(result.reasonCode()).isEqualTo(DefaultFraudScreeningService.FRAUD_REASON_CODE);
        assertThat(result.description()).contains("debtor account on denylist");
    }

    @Test
    void creditorOnDenylistIsBlocked() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("100000"), 1000, 60, "BAD-CREDITOR");

        ScreeningResult result = service.screen(payment("ACC-OK", "BAD-CREDITOR", new BigDecimal("10.00")));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.BLOCK);
        assertThat(result.description()).contains("creditor account on denylist");
    }

    @Test
    void denylistShortCircuitsBeforeOtherRules() {
        // Amount and velocity would both pass; only denylist would block. Verifies priority.
        DefaultFraudScreeningService service = newService(
                new BigDecimal("100000"), 1000, 60, "BAD-DEBTOR");

        ScreeningResult result = service.screen(payment("BAD-DEBTOR", "ACC-OK", new BigDecimal("1.00")));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.BLOCK);
    }

    // ── Amount cap ───────────────────────────────────────────────────────────

    @Test
    void amountAboveCapIsBlocked() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("25000"), 1000, 60, "");

        ScreeningResult result = service.screen(payment("ACC-A", "ACC-B", new BigDecimal("25000.01")));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.BLOCK);
        assertThat(result.description()).contains("exceeds maxSingleTransferAmount");
    }

    @Test
    void amountAtCapPasses() {
        // Boundary: exactly at the cap is allowed (the rule is strictly greater than)
        DefaultFraudScreeningService service = newService(
                new BigDecimal("25000"), 1000, 60, "");

        ScreeningResult result = service.screen(payment("ACC-A", "ACC-B", new BigDecimal("25000.00")));

        // At cap is >= review threshold so it lands in REVIEW, not BLOCK
        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.REVIEW);
    }

    @Test
    void smallAmountPasses() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("25000"), 1000, 60, "");

        ScreeningResult result = service.screen(payment("ACC-A", "ACC-B", new BigDecimal("100.00")));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.PASS);
    }

    // ── Elevated-amount REVIEW ───────────────────────────────────────────────

    @Test
    void amountAtFiftyPercentOfCapTriggersReview() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("10000"), 1000, 60, "");

        ScreeningResult result = service.screen(payment("ACC-A", "ACC-B", new BigDecimal("5000.00")));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.REVIEW);
        assertThat(result.reasonCode()).isEqualTo(DefaultFraudScreeningService.REVIEW_REASON_CODE);
        assertThat(result.description()).contains("50% of maxSingleTransferAmount");
    }

    @Test
    void amountJustBelowReviewThresholdPasses() {
        DefaultFraudScreeningService service = newService(
                new BigDecimal("10000"), 1000, 60, "");

        ScreeningResult result = service.screen(payment("ACC-A", "ACC-B", new BigDecimal("4999.99")));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.PASS);
    }

    // ── Null handling ────────────────────────────────────────────────────────

    @Test
    void nullAmountPassesAmountAndReviewRules() {
        // A null amount cannot be evaluated against the cap; the rule must not NPE
        DefaultFraudScreeningService service = newService(
                new BigDecimal("10000"), 1000, 60, "");

        // Null amount + null debtor → no velocity check either, just PASS
        Pacs008Message message = Pacs008Message.builder()
                .endToEndId("E2E-NULL")
                .transactionId("TXN-NULL")
                .interbankSettlementAmount(null)
                .debtorAccountNumber(null)
                .creditorAccountNumber("ACC-OK")
                .build();

        ScreeningResult result = service.screen(message);

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.PASS);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private DefaultFraudScreeningService newService(BigDecimal max, int velocity, int windowSec,
                                                    String denylist) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // Default: velocity counter returns 1 — well below any sane limit
        when(ops.increment(any(String.class))).thenReturn(1L);
        return new DefaultFraudScreeningService(
                redis, new SimpleMeterRegistry(), max, velocity, windowSec, denylist);
    }

    private static Pacs008Message payment(String debtor, String creditor, BigDecimal amount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + debtor + "-" + creditor)
                .endToEndId("E2E-" + debtor + "-" + creditor)
                .transactionId("TXN-" + debtor + "-" + creditor)
                .interbankSettlementAmount(amount)
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(debtor)
                .creditorAccountNumber(creditor)
                .build();
    }
}
