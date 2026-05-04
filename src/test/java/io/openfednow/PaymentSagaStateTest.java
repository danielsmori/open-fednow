package io.openfednow;

import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.PaymentSaga.SagaState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the PaymentSaga state machine and CoreBankingResponse contract.
 *
 * <p>The Saga pattern manages distributed transactions across FedNow, the Shadow Ledger,
 * and the core banking system. These tests verify:
 * <ul>
 *   <li>The state machine has the correct states and initial state</li>
 *   <li>Saga IDs and transaction IDs are preserved correctly</li>
 *   <li>CoreBankingResponse correctly classifies vendor responses</li>
 *   <li>pacs.002 responses correctly carry ISO 20022 status codes</li>
 * </ul>
 */
class PaymentSagaStateTest {

    // --- PaymentSaga state machine structure ---

    @Test
    void sagaInitializesWithInitiatedState() {
        PaymentSaga saga = new PaymentSaga("SAGA-001", "TXN-001");
        assertThat(saga.getState()).isEqualTo(SagaState.INITIATED);
    }

    @Test
    void sagaPreservesIdentifiers() {
        PaymentSaga saga = new PaymentSaga("SAGA-XYZ", "TXN-ABC");
        assertThat(saga.getSagaId()).isEqualTo("SAGA-XYZ");
        assertThat(saga.getTransactionId()).isEqualTo("TXN-ABC");
    }

    @Test
    void sagaHasAllRequiredStates() {
        // Verifies the state machine covers all phases of the payment lifecycle:
        // INITIATED → FUNDS_RESERVED → CORE_SUBMITTED → FEDNOW_CONFIRMED → COMPLETED
        // Plus: COMPENSATING and FAILED for error paths
        SagaState[] states = SagaState.values();
        assertThat(states).contains(
                SagaState.INITIATED,
                SagaState.FUNDS_RESERVED,
                SagaState.CORE_SUBMITTED,
                SagaState.FEDNOW_CONFIRMED,
                SagaState.COMPLETED,
                SagaState.COMPENSATING,
                SagaState.FAILED
        );
    }

    @Test
    void sagaHappyPathStatesAreOrdered() {
        // The happy path states must be declared in the correct sequence
        // for compensation logic to determine which steps need to be reversed
        SagaState[] states = SagaState.values();
        int initiated = indexOf(states, SagaState.INITIATED);
        int fundsReserved = indexOf(states, SagaState.FUNDS_RESERVED);
        int coreSubmitted = indexOf(states, SagaState.CORE_SUBMITTED);
        int fednowConfirmed = indexOf(states, SagaState.FEDNOW_CONFIRMED);
        int completed = indexOf(states, SagaState.COMPLETED);

        assertThat(initiated).isLessThan(fundsReserved);
        assertThat(fundsReserved).isLessThan(coreSubmitted);
        assertThat(coreSubmitted).isLessThan(fednowConfirmed);
        assertThat(fednowConfirmed).isLessThan(completed);
    }

    // --- CoreBankingResponse status classification ---

    @Test
    void acceptedResponseIsCorrectlyClassified() {
        CoreBankingResponse response = new CoreBankingResponse(
                CoreBankingResponse.Status.ACCEPTED, null, "00", "REF-001");
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.isRejected()).isFalse();
        assertThat(response.isPending()).isFalse();
    }

    @Test
    void rejectedResponseIsCorrectlyClassified() {
        CoreBankingResponse response = new CoreBankingResponse(
                CoreBankingResponse.Status.REJECTED, "AM04", "NSF", "REF-002");
        assertThat(response.isRejected()).isTrue();
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AM04");
    }

    @Test
    void pendingResponseIsCorrectlyClassified() {
        CoreBankingResponse response = new CoreBankingResponse(
                CoreBankingResponse.Status.PENDING, null, "ASYNC", "REF-003");
        assertThat(response.isPending()).isTrue();
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.isRejected()).isFalse();
    }

    @Test
    void timeoutResponseIsNotAccepted() {
        // A TIMEOUT from the core triggers the SyncAsyncBridge's provisional
        // acceptance path — the transaction is NOT immediately failed
        CoreBankingResponse response = new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, null, null);
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.isRejected()).isFalse();
        assertThat(response.isPending()).isFalse();
        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
    }

    @Test
    void vendorStatusCodeIsPreservedOnRejection() {
        // Vendor-specific codes must be preserved for diagnostic logging
        // even after ISO 20022 mapping — they are needed for support investigations
        CoreBankingResponse response = new CoreBankingResponse(
                CoreBankingResponse.Status.REJECTED, "AC01", "ERR_INVALID_ACCOUNT", "REF-004");
        assertThat(response.getVendorStatusCode()).isEqualTo("ERR_INVALID_ACCOUNT");
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC01");
    }

    // --- pacs.002 response correctness ---

    @Test
    void provisionalAcceptancePacs002HasACSPStatus() {
        // During maintenance window, the SyncAsyncBridge returns ACSP (in-process)
        // rather than ACSC (completed) — settlement is pending core confirmation
        Pacs002Message provisional = Pacs002Message.builder()
                .originalEndToEndId("E2E-001")
                .originalTransactionId("TXN-001")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                .build();

        assertThat(provisional.getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSP);
        assertThat(provisional.getRejectReasonCode()).isNull();
    }

    @Test
    void rejectedPacs002CarriesReasonCode() {
        Pacs002Message rejected = Pacs002Message.rejected("E2E-002", "TXN-002", "AC04", "Closed account");
        assertThat(rejected.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(rejected.getRejectReasonCode()).isEqualTo("AC04");
        assertThat(rejected.getRejectReasonDescription()).isEqualTo("Closed account");
        assertThat(rejected.getOriginalEndToEndId()).isEqualTo("E2E-002");
    }

    @Test
    void acceptedPacs002HasNullRejectCode() {
        Pacs002Message accepted = Pacs002Message.accepted("E2E-003", "TXN-003");
        assertThat(accepted.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        assertThat(accepted.getRejectReasonCode()).isNull();
        assertThat(accepted.getRejectReasonDescription()).isNull();
        assertThat(accepted.getCreationDateTime()).isNotNull();
    }

    // --- Helper ---

    private int indexOf(SagaState[] states, SagaState target) {
        for (int i = 0; i < states.length; i++) {
            if (states[i] == target) return i;
        }
        return -1;
    }
}
