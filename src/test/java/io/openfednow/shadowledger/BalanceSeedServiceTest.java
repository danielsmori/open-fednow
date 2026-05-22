package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BalanceSeedService} — issue #39.
 *
 * <p>Mocks the core adapter and the Shadow Ledger so dispatch logic, error
 * isolation, and the IF_ABSENT vs OVERWRITE distinction can be verified
 * without Redis or a Spring context.
 */
class BalanceSeedServiceTest {

    // ── Property parsing ─────────────────────────────────────────────────────

    @Test
    void emptyPropertyResultsInNoConfiguredAccounts() {
        BalanceSeedService service = newService("", mock(CoreBankingAdapter.class), mock(ShadowLedger.class));
        assertThat(service.configuredSeedAccountIds()).isEmpty();
    }

    @Test
    void nullPropertyResultsInNoConfiguredAccounts() {
        BalanceSeedService service = newService(null, mock(CoreBankingAdapter.class), mock(ShadowLedger.class));
        assertThat(service.configuredSeedAccountIds()).isEmpty();
    }

    @Test
    void commaSeparatedListIsParsedAndTrimmed() {
        BalanceSeedService service = newService(
                " ACC-1 , ACC-2 ,ACC-3,, ",
                mock(CoreBankingAdapter.class), mock(ShadowLedger.class));

        assertThat(service.configuredSeedAccountIds())
                .containsExactly("ACC-1", "ACC-2", "ACC-3");
    }

    // ── Startup seed (IF_ABSENT) ─────────────────────────────────────────────

    @Test
    void startupSeedWithNoAccountsConfiguredIsNoOp() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        ShadowLedger ledger = mock(ShadowLedger.class);
        BalanceSeedService service = newService("", adapter, ledger);

        BalanceSeedReport report = service.seedOnStartup();

        assertThat(report.outcomes()).isEmpty();
        verifyNoInteractions(adapter, ledger);
    }

    @Test
    void startupSeedCallsAdapterAndLedgerOncePerAccount() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance("ACC-1")).thenReturn(new BigDecimal("100.00"));
        when(adapter.getAvailableBalance("ACC-2")).thenReturn(new BigDecimal("250.00"));
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.seedBalanceIfAbsent(any(), any())).thenReturn(true);

        BalanceSeedService service = newService("ACC-1,ACC-2", adapter, ledger);

        BalanceSeedReport report = service.seedOnStartup();

        assertThat(report.seededCount()).isEqualTo(2);
        assertThat(report.skippedCount()).isZero();
        assertThat(report.failedCount()).isZero();
        verify(adapter).getAvailableBalance("ACC-1");
        verify(adapter).getAvailableBalance("ACC-2");
        verify(ledger).seedBalanceIfAbsent("ACC-1", new BigDecimal("100.00"));
        verify(ledger).seedBalanceIfAbsent("ACC-2", new BigDecimal("250.00"));
        // OVERWRITE path must not be used on startup
        verify(ledger, never()).seedBalance(any(), any());
    }

    @Test
    void startupSeedRecordsSkippedWhenLedgerReportsAlreadyPresent() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance("ACC-LIVE")).thenReturn(new BigDecimal("500.00"));
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.seedBalanceIfAbsent(eq("ACC-LIVE"), any())).thenReturn(false);

        BalanceSeedReport report = newService("ACC-LIVE", adapter, ledger).seedOnStartup();

        assertThat(report.seededCount()).isZero();
        assertThat(report.skippedCount()).isEqualTo(1);
        assertThat(report.outcomes().get(0).status())
                .isEqualTo(BalanceSeedReport.AccountSeedOutcome.Status.SKIPPED);
    }

    @Test
    void startupSeedHandlesAdapterFailureAndContinuesWithOtherAccounts() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance("ACC-BAD"))
                .thenThrow(new RuntimeException("core unreachable"));
        when(adapter.getAvailableBalance("ACC-OK")).thenReturn(new BigDecimal("75.00"));
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.seedBalanceIfAbsent(eq("ACC-OK"), any())).thenReturn(true);

        BalanceSeedReport report = newService("ACC-BAD,ACC-OK", adapter, ledger).seedOnStartup();

        assertThat(report.seededCount()).isEqualTo(1);
        assertThat(report.failedCount()).isEqualTo(1);
        BalanceSeedReport.AccountSeedOutcome bad = report.outcomes().stream()
                .filter(o -> "ACC-BAD".equals(o.accountId())).findFirst().orElseThrow();
        assertThat(bad.status()).isEqualTo(BalanceSeedReport.AccountSeedOutcome.Status.FAILED);
        assertThat(bad.message()).contains("core unreachable");
        // Ledger must not have been touched for the failed account
        verify(ledger, never()).seedBalanceIfAbsent(eq("ACC-BAD"), any());
    }

    @Test
    void startupSeedHandlesNullBalanceFromAdapter() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance("ACC-NULL")).thenReturn(null);
        ShadowLedger ledger = mock(ShadowLedger.class);

        BalanceSeedReport report = newService("ACC-NULL", adapter, ledger).seedOnStartup();

        assertThat(report.failedCount()).isEqualTo(1);
        verify(ledger, never()).seedBalanceIfAbsent(any(), any());
    }

    // ── Admin re-seed (OVERWRITE) ────────────────────────────────────────────

    @Test
    void seedAllConfiguredUsesUnconditionalOverwrite() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance("ACC-1")).thenReturn(new BigDecimal("100.00"));
        ShadowLedger ledger = mock(ShadowLedger.class);

        BalanceSeedReport report = newService("ACC-1", adapter, ledger).seedAllConfigured();

        verify(ledger).seedBalance("ACC-1", new BigDecimal("100.00"));
        // The IF_ABSENT path must not be used by the admin endpoint
        verify(ledger, never()).seedBalanceIfAbsent(any(), any());
        assertThat(report.seededCount()).isEqualTo(1);
        assertThat(report.skippedCount()).isZero();
    }

    @Test
    void seedAllConfiguredReturnsOutcomeForEveryAccount() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance("ACC-X")).thenReturn(new BigDecimal("100.00"));
        when(adapter.getAvailableBalance("ACC-Y"))
                .thenThrow(new RuntimeException("network blip"));
        when(adapter.getAvailableBalance("ACC-Z")).thenReturn(new BigDecimal("50.00"));
        ShadowLedger ledger = mock(ShadowLedger.class);

        BalanceSeedReport report = newService("ACC-X,ACC-Y,ACC-Z", adapter, ledger).seedAllConfigured();

        assertThat(report.outcomes()).extracting(BalanceSeedReport.AccountSeedOutcome::accountId)
                .containsExactly("ACC-X", "ACC-Y", "ACC-Z");
        assertThat(report.seededCount()).isEqualTo(2);
        assertThat(report.failedCount()).isEqualTo(1);
    }

    // ── Ledger write failure isolation ───────────────────────────────────────

    @Test
    void ledgerWriteFailureIsRecordedWithoutPropagating() {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance("ACC-1")).thenReturn(new BigDecimal("100.00"));
        ShadowLedger ledger = mock(ShadowLedger.class);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(ledger).seedBalanceIfAbsent(eq("ACC-1"), any());

        BalanceSeedReport report = newService("ACC-1", adapter, ledger).seedOnStartup();

        assertThat(report.failedCount()).isEqualTo(1);
        assertThat(report.outcomes().get(0).message()).contains("redis down");
    }

    // ── Report aggregation ────────────────────────────────────────────────────

    @Test
    void emptyReportFactoryReturnsAllZeros() {
        BalanceSeedReport empty = BalanceSeedReport.empty();
        assertThat(empty.seededCount()).isZero();
        assertThat(empty.skippedCount()).isZero();
        assertThat(empty.failedCount()).isZero();
        assertThat(empty.outcomes()).isEmpty();
    }

    @Test
    void ofFactoryCountsByStatus() {
        BalanceSeedReport report = BalanceSeedReport.of(List.of(
                BalanceSeedReport.AccountSeedOutcome.seeded("A", new BigDecimal("100")),
                BalanceSeedReport.AccountSeedOutcome.seeded("B", new BigDecimal("200")),
                BalanceSeedReport.AccountSeedOutcome.skipped("C", "exists"),
                BalanceSeedReport.AccountSeedOutcome.failed("D", "boom")));

        assertThat(report.seededCount()).isEqualTo(2);
        assertThat(report.skippedCount()).isEqualTo(1);
        assertThat(report.failedCount()).isEqualTo(1);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private BalanceSeedService newService(String property, CoreBankingAdapter adapter, ShadowLedger ledger) {
        return new BalanceSeedService(ledger, adapter, property);
    }
}
