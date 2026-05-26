package io.openfednow.processing.fraud;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reference fraud-screening implementation backed by a small set of configurable rules.
 *
 * <p>The default implementation is suitable for development and reference deployments.
 * Production institutions are expected to replace this with their own
 * {@link FraudScreeningPort} implementation calling into a hosted scoring service,
 * ML model, or commercial rules engine.
 *
 * <h2>Rules</h2>
 *
 * <ol>
 *   <li><strong>Account denylist</strong> — if either the debtor or creditor account is on
 *       the configured denylist, the payment is {@code BLOCK}ed. Highest priority — a
 *       known-bad account short-circuits the other rules.</li>
 *   <li><strong>Amount cap</strong> — if the interbank settlement amount exceeds
 *       {@code openfednow.fraud.max-single-transfer-amount} (default $25,000), the
 *       payment is {@code BLOCK}ed.</li>
 *   <li><strong>Debtor velocity</strong> — if the debtor account has issued more than
 *       {@code openfednow.fraud.velocity.max-per-window} transfers within
 *       {@code openfednow.fraud.velocity.window-seconds} (defaults 10 / 60), the
 *       payment is {@code BLOCK}ed. Velocity counters are stored in Redis with a TTL
 *       equal to the window size, so the cost is constant per call and old counters
 *       expire naturally.</li>
 *   <li><strong>Elevated-amount review</strong> — if the amount exceeds half the cap
 *       but is under the cap itself, the payment receives {@code REVIEW} — it
 *       proceeds, but is flagged in logs and the {@code fraud.reviewed} counter for
 *       operational visibility.</li>
 * </ol>
 *
 * <p>Bean activation: this service registers itself when
 * {@code openfednow.fraud.enabled=true}. When disabled (the default), the
 * {@link NoOpFraudScreeningService} fallback returns {@code PASS} for every call.
 */
@Component
@ConditionalOnProperty(name = "openfednow.fraud.enabled", havingValue = "true")
public class DefaultFraudScreeningService implements FraudScreeningPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultFraudScreeningService.class);

    static final String VELOCITY_KEY_PREFIX = "fraud:velocity:";
    static final String FRAUD_REASON_CODE = "FRAD";
    static final String REVIEW_REASON_CODE = "REVW";

    static final String BLOCKED_METRIC = "fraud.blocked";
    static final String REVIEWED_METRIC = "fraud.reviewed";

    /**
     * Atomic INCR+EXPIRE for the velocity counter. The previous two-call
     * implementation had a race: if the pod died between the INCR and the
     * EXPIRE, the counter persisted with no TTL — and every subsequent call
     * from that debtor would BLOCK forever because the limit would always be
     * exceeded. The script is sent once and Redis runs it atomically: either
     * both the increment and the TTL apply, or neither does.
     *
     * <p>The script also sets the TTL on every call, not just the first.
     * That's deliberate: a sliding window matches the documented semantic
     * ("more than N transfers within the last X seconds") more closely than
     * a fixed window where the bucket rolls over only when the first entry
     * expires. The cost is one additional EXPIRE per request, which Redis
     * handles in microseconds.
     */
    private static final RedisScript<Long> VELOCITY_INCR_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('INCR', KEYS[1])\n" +
                    "redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
                    "return v",
            Long.class);

    private final BigDecimal maxAmount;
    private final int velocityLimit;
    private final int velocityWindowSeconds;
    private final Set<String> denylist;
    private final StringRedisTemplate redis;
    private final Counter blockedCounter;
    private final Counter reviewedCounter;

    public DefaultFraudScreeningService(
            StringRedisTemplate redis,
            MeterRegistry meterRegistry,
            @Value("${openfednow.fraud.max-single-transfer-amount:25000.00}") BigDecimal maxAmount,
            @Value("${openfednow.fraud.velocity.max-per-window:10}") int velocityLimit,
            @Value("${openfednow.fraud.velocity.window-seconds:60}") int velocityWindowSeconds,
            @Value("${openfednow.fraud.account-denylist:}") String denylistProperty) {
        if (maxAmount == null || maxAmount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.fraud.max-single-transfer-amount must be positive");
        }
        if (velocityLimit <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.fraud.velocity.max-per-window must be positive");
        }
        if (velocityWindowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.fraud.velocity.window-seconds must be positive");
        }
        this.redis = redis;
        this.maxAmount = maxAmount;
        this.velocityLimit = velocityLimit;
        this.velocityWindowSeconds = velocityWindowSeconds;
        this.denylist = parseDenylist(denylistProperty);
        this.blockedCounter = Counter.builder(BLOCKED_METRIC)
                .description("Number of credit transfers blocked by fraud pre-screening")
                .register(meterRegistry);
        this.reviewedCounter = Counter.builder(REVIEWED_METRIC)
                .description("Number of credit transfers flagged for manual fraud review")
                .register(meterRegistry);

        log.info("Fraud pre-screening active maxAmount={} velocity={}/{}s denylistSize={}",
                maxAmount, velocityLimit, velocityWindowSeconds, denylist.size());
    }

    private static Set<String> parseDenylist(String property) {
        if (property == null || property.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Visible for tests. */
    Set<String> getDenylist() {
        return denylist;
    }

    /** Visible for tests. */
    BigDecimal getMaxAmount() {
        return maxAmount;
    }

    @Override
    public ScreeningResult screen(Pacs008Message message) {
        // Rule 1: account denylist (highest priority — short-circuits the rest)
        String debtorAccount = message.getDebtorAccountNumber();
        if (debtorAccount != null && denylist.contains(debtorAccount)) {
            return blockAndLog(message, "debtor account on denylist account=" + debtorAccount);
        }
        String creditorAccount = message.getCreditorAccountNumber();
        if (creditorAccount != null && denylist.contains(creditorAccount)) {
            return blockAndLog(message, "creditor account on denylist account=" + creditorAccount);
        }

        // Rule 2: amount cap
        BigDecimal amount = message.getInterbankSettlementAmount();
        if (amount != null && amount.compareTo(maxAmount) > 0) {
            return blockAndLog(message,
                    "amount " + amount + " exceeds maxSingleTransferAmount " + maxAmount);
        }

        // Rule 3: debtor velocity (Redis counter with TTL = window).
        // The INCR and EXPIRE are executed atomically inside a Lua script so
        // a mid-operation pod failure cannot leave a TTL-less counter behind.
        String debtor = message.getDebtorAccountNumber();
        if (debtor != null && !debtor.isBlank()) {
            String key = VELOCITY_KEY_PREFIX + debtor;
            List<String> keys = Collections.singletonList(key);
            Long count = redis.execute(
                    VELOCITY_INCR_SCRIPT, keys, String.valueOf(velocityWindowSeconds));
            if (count != null && count > velocityLimit) {
                return blockAndLog(message,
                        "debtor velocity " + count + " exceeds " + velocityLimit
                                + " per " + velocityWindowSeconds + "s window");
            }
        }

        // Rule 4: elevated-amount review (half the cap or more, but under it)
        BigDecimal reviewThreshold = maxAmount.divide(BigDecimal.valueOf(2));
        if (amount != null && amount.compareTo(reviewThreshold) >= 0) {
            ScreeningResult review = ScreeningResult.review(REVIEW_REASON_CODE,
                    "amount " + amount + " >= 50% of maxSingleTransferAmount " + maxAmount);
            reviewedCounter.increment();
            log.warn("Fraud review flagged e2e={} txn={} reason={}",
                    message.getEndToEndId(), message.getTransactionId(), review.description());
            return review;
        }

        log.debug("Fraud screening passed e2e={} amount={}",
                message.getEndToEndId(), amount);
        return ScreeningResult.pass();
    }

    private ScreeningResult blockAndLog(Pacs008Message message, String description) {
        blockedCounter.increment();
        log.warn("Fraud screening BLOCKED e2e={} txn={} reason={}",
                message.getEndToEndId(), message.getTransactionId(), description);
        return ScreeningResult.block(FRAUD_REASON_CODE, description);
    }
}
