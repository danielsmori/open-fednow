package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract contract test — every {@link CoreBankingAdapter} implementation must pass.
 *
 * <p>Defines the behavioral contract that all adapters (SandboxAdapter, MockVendorAdapter,
 * and future production adapters for Fiserv, FIS, Jack Henry) must satisfy. Concrete
 * subclasses supply the adapter under test via {@link #adapter()}.
 *
 * <p>Contract requirements:
 * <ul>
 *   <li>{@code postCreditTransfer} returns a non-null {@link CoreBankingResponse}</li>
 *   <li>Normal payment → ACCEPTED status</li>
 *   <li>Account prefix {@code RJCT_FUNDS_} → REJECTED with ISO 20022 code AM04</li>
 *   <li>Account prefix {@code RJCT_ACCT_} → REJECTED with ISO 20022 code AC01</li>
 *   <li>Account prefix {@code RJCT_CLOSED_} → REJECTED with ISO 20022 code AC04</li>
 *   <li>Account prefix {@code TOUT_} → TIMEOUT (no reason code required)</li>
 *   <li>Any REJECTED response includes a non-null ISO 20022 reason code</li>
 *   <li>{@code getAvailableBalance} returns a non-negative BigDecimal</li>
 *   <li>{@code isCoreSystemAvailable} returns without throwing</li>
 *   <li>{@code getVendorName} returns a non-null, non-empty string</li>
 * </ul>
 *
 * <p>Future vendor adapters (Fiserv, FIS, Jack Henry) must extend this class. If an
 * adapter cannot satisfy a contract item, that item must be replaced with a documented
 * exception and an explanatory comment.
 */
public abstract class CoreBankingAdapterContractTest {

    /** Returns the adapter under test. Called once per test method. */
    protected abstract CoreBankingAdapter adapter();

    // ── postCreditTransfer: normal payment ────────────────────────────────────

    @Test
    void postCreditTransfer_returnsNonNullResponse() {
        CoreBankingResponse response = adapter().postCreditTransfer(payment("ACC-001"));
        assertThat(response).isNotNull();
    }

    @Test
    void postCreditTransfer_normalPayment_returnsAccepted() {
        CoreBankingResponse response = adapter().postCreditTransfer(payment("ACC-NORMAL-001"));
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.ACCEPTED);
    }

    @Test
    void postCreditTransfer_normalPayment_isAcceptedConvenienceMethod() {
        CoreBankingResponse response = adapter().postCreditTransfer(payment("ACC-NORMAL-002"));
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.isRejected()).isFalse();
    }

    // ── postCreditTransfer: rejection scenarios ───────────────────────────────

    @Test
    void postCreditTransfer_insufficientFundsPrefix_returnsRejectedAm04() {
        CoreBankingResponse response = adapter()
                .postCreditTransfer(payment(SandboxAdapter.PREFIX_REJECT_FUNDS + "ACC-001"));
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.REJECTED);
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AM04");
    }

    @Test
    void postCreditTransfer_invalidAccountPrefix_returnsRejectedAc01() {
        CoreBankingResponse response = adapter()
                .postCreditTransfer(payment(SandboxAdapter.PREFIX_REJECT_ACCOUNT + "ACC-001"));
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.REJECTED);
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC01");
    }

    @Test
    void postCreditTransfer_closedAccountPrefix_returnsRejectedAc04() {
        CoreBankingResponse response = adapter()
                .postCreditTransfer(payment(SandboxAdapter.PREFIX_REJECT_CLOSED + "ACC-001"));
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.REJECTED);
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC04");
    }

    @Test
    void postCreditTransfer_anyRejectedResponse_hasIso20022ReasonCode() {
        // Covers all three rejection scenarios
        for (String prefix : new String[]{
                SandboxAdapter.PREFIX_REJECT_FUNDS,
                SandboxAdapter.PREFIX_REJECT_ACCOUNT,
                SandboxAdapter.PREFIX_REJECT_CLOSED}) {
            CoreBankingResponse r = adapter().postCreditTransfer(payment(prefix + "ACC-CONTRACT"));
            assertThat(r.getIso20022ReasonCode())
                    .as("ISO 20022 reason code must be non-null for REJECTED status (prefix=%s)", prefix)
                    .isNotNull()
                    .isNotBlank();
        }
    }

    // ── postCreditTransfer: timeout ───────────────────────────────────────────

    @Test
    void postCreditTransfer_timeoutPrefix_returnsTimeout() {
        CoreBankingResponse response = adapter()
                .postCreditTransfer(payment(SandboxAdapter.PREFIX_TIMEOUT + "ACC-001"));
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
    }

    @Test
    void postCreditTransfer_timeoutResponse_doesNotRequireReasonCode() {
        // TIMEOUT responses do not need an ISO 20022 reason code —
        // the SyncAsyncBridge maps TIMEOUT to ACSP provisional acceptance.
        CoreBankingResponse response = adapter()
                .postCreditTransfer(payment(SandboxAdapter.PREFIX_TIMEOUT + "ACC-001"));
        // No assertion on reason code — the contract does not require it for TIMEOUT
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
    }

    // ── getAvailableBalance ───────────────────────────────────────────────────

    @Test
    void getAvailableBalance_returnsNonNegativeValue() {
        BigDecimal balance = adapter().getAvailableBalance("ACC-BALANCE-001");
        assertThat(balance).isNotNull();
        assertThat(balance.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void getAvailableBalance_returnsScaledDecimal() {
        BigDecimal balance = adapter().getAvailableBalance("ACC-BALANCE-002");
        // Must have no more than 2 decimal places (USD cents precision)
        assertThat(balance.scale()).isLessThanOrEqualTo(2);
    }

    // ── isCoreSystemAvailable ─────────────────────────────────────────────────

    @Test
    void isCoreSystemAvailable_doesNotThrow() {
        // Contract: must return without throwing, even if the result is false
        boolean result = adapter().isCoreSystemAvailable();
        assertThat(result).isIn(true, false); // just verify it returns a boolean
    }

    // ── getVendorName ─────────────────────────────────────────────────────────

    @Test
    void getVendorName_returnsNonNullNonEmptyString() {
        String name = adapter().getVendorName();
        assertThat(name).isNotNull().isNotBlank();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    protected static Pacs008Message payment(String creditorAccount) {
        return Pacs008Message.builder()
                .messageId("MSG-CONTRACT-001")
                .endToEndId("E2E-CONTRACT-001")
                .transactionId("TXN-CONTRACT-001")
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .creditorAccountNumber(creditorAccount)
                .debtorAccountNumber("ACC-DEBTOR-CONTRACT")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorName("Test Debtor")
                .creditorName("Test Creditor")
                .numberOfTransactions(1)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }
}
