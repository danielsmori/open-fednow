package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link BalanceSeedService} — issue #39.
 *
 * <p>Drives the IF_ABSENT / OVERWRITE distinction against a real Redis container.
 * The sandbox adapter is bypassed via a Mockito spy so per-account return values
 * can be controlled without depending on sandbox prefix conventions.
 */
@TestPropertySource(properties = "openfednow.shadow-ledger.seed-accounts=ACC-INT-A,ACC-INT-B")
class BalanceSeedIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private BalanceSeedService balanceSeedService;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @org.springframework.boot.test.mock.mockito.SpyBean
    private CoreBankingAdapter coreBankingAdapter;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys("balance:*"));
    }

    @Test
    void startupSeedPopulatesEmptyAccountsFromCore() {
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-A")))
                .thenReturn(new BigDecimal("500.00"));
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-B")))
                .thenReturn(new BigDecimal("750.00"));

        BalanceSeedReport report = balanceSeedService.seedOnStartup();

        assertThat(report.seededCount()).isEqualTo(2);
        assertThat(shadowLedger.getAvailableBalance("ACC-INT-A"))
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(shadowLedger.getAvailableBalance("ACC-INT-B"))
                .isEqualByComparingTo(new BigDecimal("750.00"));
    }

    @Test
    void startupSeedDoesNotOverwriteLiveBalances() {
        // Pre-populate ACC-INT-A with a "live" balance that must be preserved
        redis.opsForValue().set("balance:ACC-INT-A", "20000"); // $200.00
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-A")))
                .thenReturn(new BigDecimal("999.99"));
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-B")))
                .thenReturn(new BigDecimal("100.00"));

        BalanceSeedReport report = balanceSeedService.seedOnStartup();

        // ACC-INT-A was skipped; ACC-INT-B was seeded fresh
        assertThat(report.seededCount()).isEqualTo(1);
        assertThat(report.skippedCount()).isEqualTo(1);
        assertThat(shadowLedger.getAvailableBalance("ACC-INT-A"))
                .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(shadowLedger.getAvailableBalance("ACC-INT-B"))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void adminSeedUnconditionallyOverwritesExistingBalances() {
        redis.opsForValue().set("balance:ACC-INT-A", "20000"); // $200.00
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-A")))
                .thenReturn(new BigDecimal("999.99"));
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-B")))
                .thenReturn(new BigDecimal("100.00"));

        BalanceSeedReport report = balanceSeedService.seedAllConfigured();

        assertThat(report.seededCount()).isEqualTo(2);
        assertThat(report.skippedCount()).isZero();
        // ACC-INT-A was overwritten — the admin endpoint replaces, not preserves
        assertThat(shadowLedger.getAvailableBalance("ACC-INT-A"))
                .isEqualByComparingTo(new BigDecimal("999.99"));
        assertThat(shadowLedger.getAvailableBalance("ACC-INT-B"))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void seedIsolatesAdapterFailureToOneAccount() {
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-A")))
                .thenThrow(new RuntimeException("network blip"));
        when(coreBankingAdapter.getAvailableBalance(eq("ACC-INT-B")))
                .thenReturn(new BigDecimal("250.00"));

        BalanceSeedReport report = balanceSeedService.seedOnStartup();

        assertThat(report.seededCount()).isEqualTo(1);
        assertThat(report.failedCount()).isEqualTo(1);
        // The successful account was still seeded
        assertThat(shadowLedger.getAvailableBalance("ACC-INT-B"))
                .isEqualByComparingTo(new BigDecimal("250.00"));
        // The failed account has no Redis entry
        assertThat(redis.hasKey("balance:ACC-INT-A")).isFalse();
    }
}
