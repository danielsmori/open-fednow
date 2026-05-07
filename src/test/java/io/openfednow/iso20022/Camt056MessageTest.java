package io.openfednow.iso20022;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Camt056Message}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Builder populates all fields correctly</li>
 *   <li>{@code forPaymentCancellation} derives the correct fields from the original pacs.008</li>
 *   <li>Unique message IDs are generated for each call</li>
 *   <li>caseId links back to the original transaction</li>
 *   <li>All supported cancellation reason codes are carried through</li>
 * </ul>
 */
class Camt056MessageTest {

    // --- Builder ---

    @Test
    void builderSetsAllFields() {
        OffsetDateTime now = OffsetDateTime.now();

        Camt056Message msg = Camt056Message.builder()
                .messageId("CNCL-001")
                .creationDateTime(now)
                .caseId("CASE-TXN-001")
                .originalMessageId("MSG-001")
                .originalEndToEndId("E2E-001")
                .originalTransactionId("TXN-001")
                .originalInterbankSettlementAmount(new BigDecimal("750.00"))
                .originalInterbankSettlementCurrency("USD")
                .cancellationReasonCode("DUPL")
                .cancellationReasonDescription("Duplicate of E2E-000")
                .build();

        assertThat(msg.getMessageId()).isEqualTo("CNCL-001");
        assertThat(msg.getCreationDateTime()).isEqualTo(now);
        assertThat(msg.getCaseId()).isEqualTo("CASE-TXN-001");
        assertThat(msg.getOriginalMessageId()).isEqualTo("MSG-001");
        assertThat(msg.getOriginalEndToEndId()).isEqualTo("E2E-001");
        assertThat(msg.getOriginalTransactionId()).isEqualTo("TXN-001");
        assertThat(msg.getOriginalInterbankSettlementAmount()).isEqualByComparingTo("750.00");
        assertThat(msg.getOriginalInterbankSettlementCurrency()).isEqualTo("USD");
        assertThat(msg.getCancellationReasonCode()).isEqualTo("DUPL");
        assertThat(msg.getCancellationReasonDescription()).isEqualTo("Duplicate of E2E-000");
    }

    // --- forPaymentCancellation: field derivation from pacs.008 ---

    @Test
    void forPaymentCancellationPreservesOriginalReferences() {
        Pacs008Message original = sampleTransfer();

        Camt056Message cncl = Camt056Message.forPaymentCancellation(original, "DUPL", null);

        assertThat(cncl.getOriginalMessageId()).isEqualTo(original.getMessageId());
        assertThat(cncl.getOriginalEndToEndId()).isEqualTo(original.getEndToEndId());
        assertThat(cncl.getOriginalTransactionId()).isEqualTo(original.getTransactionId());
    }

    @Test
    void forPaymentCancellationPreservesOriginalAmount() {
        Pacs008Message original = sampleTransfer();

        Camt056Message cncl = Camt056Message.forPaymentCancellation(original, "DUPL", null);

        assertThat(cncl.getOriginalInterbankSettlementAmount())
                .isEqualByComparingTo(original.getInterbankSettlementAmount());
        assertThat(cncl.getOriginalInterbankSettlementCurrency())
                .isEqualTo(original.getInterbankSettlementCurrency());
    }

    @Test
    void forPaymentCancellationSetsCreationDateTime() {
        Camt056Message cncl = Camt056Message.forPaymentCancellation(
                sampleTransfer(), "CUST", null);

        assertThat(cncl.getCreationDateTime()).isNotNull();
    }

    @Test
    void forPaymentCancellationGeneratesUniqueMessageIds() {
        Pacs008Message original = sampleTransfer();

        Camt056Message cncl1 = Camt056Message.forPaymentCancellation(original, "DUPL", null);
        Camt056Message cncl2 = Camt056Message.forPaymentCancellation(original, "DUPL", null);

        assertThat(cncl1.getMessageId()).isNotEqualTo(cncl2.getMessageId());
    }

    @Test
    void forPaymentCancellationCaseIdContainsOriginalTransactionId() {
        Pacs008Message original = sampleTransfer();

        Camt056Message cncl = Camt056Message.forPaymentCancellation(original, "DUPL", null);

        assertThat(cncl.getCaseId()).contains(original.getTransactionId());
    }

    // --- Reason codes ---

    @Test
    void forPaymentCancellationWithDuplReasonCode() {
        Camt056Message cncl = Camt056Message.forPaymentCancellation(
                sampleTransfer(), "DUPL", "Exact duplicate of E2E-20240114-099");

        assertThat(cncl.getCancellationReasonCode()).isEqualTo("DUPL");
        assertThat(cncl.getCancellationReasonDescription()).contains("E2E-20240114-099");
    }

    @Test
    void forPaymentCancellationWithFraudReasonCode() {
        Camt056Message cncl = Camt056Message.forPaymentCancellation(
                sampleTransfer(), "FRAUD", "Unauthorised transaction detected");

        assertThat(cncl.getCancellationReasonCode()).isEqualTo("FRAUD");
    }

    @Test
    void forPaymentCancellationWithCustReasonCode() {
        Camt056Message cncl = Camt056Message.forPaymentCancellation(
                sampleTransfer(), "CUST", null);

        assertThat(cncl.getCancellationReasonCode()).isEqualTo("CUST");
    }

    @Test
    void forPaymentCancellationWithNarrReasonCode() {
        String narrative = "Customer called to cancel — wrong beneficiary account";

        Camt056Message cncl = Camt056Message.forPaymentCancellation(
                sampleTransfer(), "NARR", narrative);

        assertThat(cncl.getCancellationReasonCode()).isEqualTo("NARR");
        assertThat(cncl.getCancellationReasonDescription()).isEqualTo(narrative);
    }

    // --- Helper ---

    private Pacs008Message sampleTransfer() {
        return Pacs008Message.builder()
                .messageId("MSG-ORIG-001")
                .transactionId("TXN-ORIG-001")
                .endToEndId("E2E-ORIG-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal("1000.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("011000138")
                .debtorAccountNumber("123456789")
                .creditorAccountNumber("987654321")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .build();
    }
}
