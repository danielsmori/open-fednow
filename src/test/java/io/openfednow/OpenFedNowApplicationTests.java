package io.openfednow;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic sanity tests for ISO 20022 message models and factory methods.
 * Full integration tests will be added as adapter implementations are completed.
 */
class OpenFedNowApplicationTests {

    @Test
    void pacs008MessageBuildsCorrectly() {
        Pacs008Message message = Pacs008Message.builder()
                .messageId("MSG-001")
                .endToEndId("E2E-001")
                .transactionId("TXN-001")
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("123456789")
                .creditorAccountNumber("987654321")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .build();

        assertThat(message.getMessageId()).isEqualTo("MSG-001");
        assertThat(message.getInterbankSettlementAmount()).isEqualByComparingTo("100.00");
        assertThat(message.getInterbankSettlementCurrency()).isEqualTo("USD");
        assertThat(message.getNumberOfTransactions()).isEqualTo(1);
    }

    @Test
    void pacs002AcceptedFactoryReturnsCorrectStatus() {
        Pacs002Message response = Pacs002Message.accepted("E2E-001", "TXN-001");

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        assertThat(response.getOriginalEndToEndId()).isEqualTo("E2E-001");
        assertThat(response.getRejectReasonCode()).isNull();
        assertThat(response.getCreationDateTime()).isNotNull();
    }

    @Test
    void pacs002RejectedFactoryReturnsCorrectStatus() {
        Pacs002Message response = Pacs002Message.rejected("E2E-001", "TXN-001", "AM04", "Insufficient funds");

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("AM04");
        assertThat(response.getRejectReasonDescription()).isEqualTo("Insufficient funds");
    }

    @Test
    void coreBankingResponseIsAcceptedCheck() {
        io.openfednow.acl.core.CoreBankingResponse accepted =
                new io.openfednow.acl.core.CoreBankingResponse(
                        io.openfednow.acl.core.CoreBankingResponse.Status.ACCEPTED,
                        null, "00", "REF-001");

        assertThat(accepted.isAccepted()).isTrue();
        assertThat(accepted.isRejected()).isFalse();
    }

    @Test
    void paymentSagaInitializesWithCorrectState() {
        io.openfednow.processing.saga.PaymentSaga saga =
                new io.openfednow.processing.saga.PaymentSaga("SAGA-001", "TXN-001");

        assertThat(saga.getSagaId()).isEqualTo("SAGA-001");
        assertThat(saga.getTransactionId()).isEqualTo("TXN-001");
        assertThat(saga.getState()).isEqualTo(
                io.openfednow.processing.saga.PaymentSaga.SagaState.INITIATED);
    }
}
