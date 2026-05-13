package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link MockVendorAdapter}.
 *
 * <p>Extends {@link CoreBankingAdapterContractTest} to prove MockVendorAdapter satisfies
 * the full adapter contract, then adds MockVendorAdapter-specific tests for its
 * in-memory balance ledger and configurable failure modes.
 */
class MockVendorAdapterContractTest extends CoreBankingAdapterContractTest {

    private MockVendorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MockVendorAdapter();
        adapter.reset();
    }

    @Override
    protected CoreBankingAdapter adapter() {
        return adapter;
    }

    // ── Balance ledger ────────────────────────────────────────────────────────

    @Test
    void unseededAccount_returnsDefaultBalance() {
        BigDecimal balance = adapter.getAvailableBalance("ACC-UNSEEDED");
        assertThat(balance).isEqualByComparingTo(MockVendorAdapter.DEFAULT_BALANCE);
    }

    @Test
    void seededAccount_returnsSeededBalance() {
        adapter.seedBalance("ACC-SEEDED-001", new BigDecimal("500.00"));
        assertThat(adapter.getAvailableBalance("ACC-SEEDED-001"))
                .isEqualByComparingTo("500.00");
    }

    @Test
    void successfulCreditTransfer_updatesLedgerBalance() {
        adapter.seedBalance("ACC-LEDGER-001", new BigDecimal("1000.00"));
        adapter.postCreditTransfer(payment("ACC-LEDGER-001"));
        // Payment amount is 100.00 (from payment() helper), so balance should be 1100.00
        assertThat(adapter.getAvailableBalance("ACC-LEDGER-001"))
                .isEqualByComparingTo("1100.00");
    }

    @Test
    void reset_clearsSeededBalances() {
        adapter.seedBalance("ACC-RESET-001", new BigDecimal("999.00"));
        adapter.reset();
        assertThat(adapter.getAvailableBalance("ACC-RESET-001"))
                .isEqualByComparingTo(MockVendorAdapter.DEFAULT_BALANCE);
    }

    // ── Availability toggle ───────────────────────────────────────────────────

    @Test
    void coreAvailableByDefault() {
        assertThat(adapter.isCoreSystemAvailable()).isTrue();
    }

    @Test
    void setCoreAvailableFalse_returnsUnavailable() {
        adapter.setCoreAvailable(false);
        assertThat(adapter.isCoreSystemAvailable()).isFalse();
    }

    @Test
    void reset_restoresCoreAvailability() {
        adapter.setCoreAvailable(false);
        adapter.reset();
        assertThat(adapter.isCoreSystemAvailable()).isTrue();
    }

    // ── Timeout simulation ────────────────────────────────────────────────────

    @Test
    void setSimulateTimeout_returnsTimeoutForAnyAccount() {
        adapter.setSimulateTimeout(true);
        CoreBankingResponse response = adapter.postCreditTransfer(payment("ACC-NORMAL-001"));
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
    }

    @Test
    void reset_clearsTimeoutSimulation() {
        adapter.setSimulateTimeout(true);
        adapter.reset();
        CoreBankingResponse response = adapter.postCreditTransfer(payment("ACC-NORMAL-002"));
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.ACCEPTED);
    }
}
