package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.acl.core.CoreBankingResponse.Status;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SandboxAdapter}.
 *
 * <p>Verifies that each scenario prefix routes to the correct
 * {@link CoreBankingResponse} status and ISO 20022 reason code, and that
 * the balance and availability helpers behave according to their contracts.
 *
 * <p>No Spring context is loaded — the adapter is constructed directly using
 * its package-private constructor so that configuration values can be
 * supplied without {@code @Value} injection.
 */
class SandboxAdapterTest {

    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("50000.00");

    private SandboxAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SandboxAdapter(true, DEFAULT_BALANCE, 0);
    }

    // --- postCreditTransfer: happy path ---

    @Test
    void normalAccountReturnsAccepted() {
        CoreBankingResponse response = adapter.postCreditTransfer(transfer("123456789"));

        assertThat(response.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(response.getIso20022ReasonCode()).isNull();
        assertThat(response.getVendorStatusCode()).isEqualTo("SANDBOX_OK");
    }

    @Test
    void transactionReferenceContainsTransactionId() {
        CoreBankingResponse response = adapter.postCreditTransfer(transfer("123456789"));

        assertThat(response.getTransactionReference()).startsWith("SANDBOX-");
        assertThat(response.getTransactionReference()).contains("TXN-TEST-001");
    }

    // --- postCreditTransfer: rejection scenarios ---

    @Test
    void insufficientFundsAccountReturnsRejectedWithAM04() {
        CoreBankingResponse response = adapter.postCreditTransfer(
                transfer(SandboxAdapter.PREFIX_REJECT_FUNDS + "999"));

        assertThat(response.getStatus()).isEqualTo(Status.REJECTED);
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AM04");
        assertThat(response.getVendorStatusCode()).isEqualTo("SANDBOX_INSUFFICIENT_FUNDS");
    }

    @Test
    void invalidAccountReturnsRejectedWithAC01() {
        CoreBankingResponse response = adapter.postCreditTransfer(
                transfer(SandboxAdapter.PREFIX_REJECT_ACCOUNT + "999"));

        assertThat(response.getStatus()).isEqualTo(Status.REJECTED);
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC01");
        assertThat(response.getVendorStatusCode()).isEqualTo("SANDBOX_INVALID_ACCOUNT");
    }

    @Test
    void closedAccountReturnsRejectedWithAC04() {
        CoreBankingResponse response = adapter.postCreditTransfer(
                transfer(SandboxAdapter.PREFIX_REJECT_CLOSED + "999"));

        assertThat(response.getStatus()).isEqualTo(Status.REJECTED);
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC04");
        assertThat(response.getVendorStatusCode()).isEqualTo("SANDBOX_CLOSED_ACCOUNT");
    }

    // --- postCreditTransfer: timeout and pending ---

    @Test
    void timeoutAccountReturnsTimeout() {
        // TIMEOUT triggers the SyncAsyncBridge provisional-acceptance path —
        // it is not a final rejection
        CoreBankingResponse response = adapter.postCreditTransfer(
                transfer(SandboxAdapter.PREFIX_TIMEOUT + "999"));

        assertThat(response.getStatus()).isEqualTo(Status.TIMEOUT);
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.isRejected()).isFalse();
        assertThat(response.getVendorStatusCode()).isEqualTo("SANDBOX_TIMEOUT");
    }

    @Test
    void pendingAccountReturnsPending() {
        CoreBankingResponse response = adapter.postCreditTransfer(
                transfer(SandboxAdapter.PREFIX_PENDING + "999"));

        assertThat(response.getStatus()).isEqualTo(Status.PENDING);
        assertThat(response.isPending()).isTrue();
        assertThat(response.getVendorStatusCode()).isEqualTo("SANDBOX_PENDING");
    }

    // --- postCreditTransfer: null account ---

    @Test
    void nullCreditorAccountDefaultsToAccepted() {
        // Null account number should not throw — fall through to default ACCEPTED
        CoreBankingResponse response = adapter.postCreditTransfer(transfer(null));

        assertThat(response.getStatus()).isEqualTo(Status.ACCEPTED);
    }

    // --- getAvailableBalance ---

    @Test
    void normalAccountReturnsDefaultBalance() {
        BigDecimal balance = adapter.getAvailableBalance("123456789");

        assertThat(balance).isEqualByComparingTo(DEFAULT_BALANCE);
    }

    @Test
    void lowBalanceAccountReturnsOneDollar() {
        BigDecimal balance = adapter.getAvailableBalance(
                SandboxAdapter.PREFIX_LOW_BALANCE + "999");

        assertThat(balance).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void nullAccountIdReturnsDefaultBalance() {
        BigDecimal balance = adapter.getAvailableBalance(null);

        assertThat(balance).isEqualByComparingTo(DEFAULT_BALANCE);
    }

    // --- isCoreSystemAvailable ---

    @Test
    void coreIsAvailableByDefault() {
        assertThat(adapter.isCoreSystemAvailable()).isTrue();
    }

    @Test
    void coreUnavailableWhenConfiguredFalse() {
        SandboxAdapter unavailable = new SandboxAdapter(false, DEFAULT_BALANCE, 0);
        assertThat(unavailable.isCoreSystemAvailable()).isFalse();
    }

    // --- getVendorName ---

    @Test
    void vendorNameIsSandbox() {
        assertThat(adapter.getVendorName()).isEqualTo("Sandbox");
    }

    // --- latency simulation ---

    @Test
    void latencySimulationDoesNotThrow() {
        // Verify that a small artificial delay completes without error.
        // A 10 ms delay is sufficient to exercise the Thread.sleep path.
        SandboxAdapter withLatency = new SandboxAdapter(true, DEFAULT_BALANCE, 10);
        CoreBankingResponse response = withLatency.postCreditTransfer(transfer("123456789"));
        assertThat(response.getStatus()).isEqualTo(Status.ACCEPTED);
    }

    // --- Helper ---

    private Pacs008Message transfer(String creditorAccountNumber) {
        return Pacs008Message.builder()
                .messageId("MSG-001")
                .transactionId("TXN-TEST-001")
                .endToEndId("E2E-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("011000138")
                .debtorAccountNumber("987654321")
                .creditorAccountNumber(creditorAccountNumber)
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .remittanceInformation("Invoice 1234")
                .build();
    }
}
