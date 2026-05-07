package io.openfednow.iso20022;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Pacs004Message}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Builder sets all fields correctly</li>
 *   <li>{@code forSagaCompensation} derives correct fields from the original pacs.008</li>
 *   <li>Debtor/creditor agent routing numbers are correctly reversed in the return</li>
 *   <li>Common return reason codes are carried through unchanged</li>
 * </ul>
 */
class Pacs004MessageTest {

    // --- Builder ---

    @Test
    void builderSetsAllFields() {
        OffsetDateTime now = OffsetDateTime.now();

        Pacs004Message msg = Pacs004Message.builder()
                .messageId("MSG-RTN-001")
                .creationDateTime(now)
                .returnId("RTNID-001")
                .originalMessageId("MSG-001")
                .originalEndToEndId("E2E-001")
                .originalTransactionId("TXN-001")
                .returnedAmount(new BigDecimal("250.00"))
                .returnedAmountCurrency("USD")
                .returnReasonCode("AM04")
                .returnReasonDescription("Core rejected on final posting")
                .returningAgentRoutingNumber("011000138")
                .receivingAgentRoutingNumber("021000021")
                .build();

        assertThat(msg.getMessageId()).isEqualTo("MSG-RTN-001");
        assertThat(msg.getCreationDateTime()).isEqualTo(now);
        assertThat(msg.getReturnId()).isEqualTo("RTNID-001");
        assertThat(msg.getOriginalMessageId()).isEqualTo("MSG-001");
        assertThat(msg.getOriginalEndToEndId()).isEqualTo("E2E-001");
        assertThat(msg.getOriginalTransactionId()).isEqualTo("TXN-001");
        assertThat(msg.getReturnedAmount()).isEqualByComparingTo("250.00");
        assertThat(msg.getReturnedAmountCurrency()).isEqualTo("USD");
        assertThat(msg.getReturnReasonCode()).isEqualTo("AM04");
        assertThat(msg.getReturnReasonDescription()).isEqualTo("Core rejected on final posting");
        assertThat(msg.getReturningAgentRoutingNumber()).isEqualTo("011000138");
        assertThat(msg.getReceivingAgentRoutingNumber()).isEqualTo("021000021");
    }

    // --- forSagaCompensation: field derivation ---

    @Test
    void sagaCompensationPreservesOriginalReferences() {
        Pacs008Message original = sampleTransfer();

        Pacs004Message rtn = Pacs004Message.forSagaCompensation(original, "AM04", "Core rejected");

        assertThat(rtn.getOriginalMessageId()).isEqualTo(original.getMessageId());
        assertThat(rtn.getOriginalEndToEndId()).isEqualTo(original.getEndToEndId());
        assertThat(rtn.getOriginalTransactionId()).isEqualTo(original.getTransactionId());
    }

    @Test
    void sagaCompensationPreservesAmount() {
        Pacs008Message original = sampleTransfer();

        Pacs004Message rtn = Pacs004Message.forSagaCompensation(original, "AM04", "Core rejected");

        assertThat(rtn.getReturnedAmount()).isEqualByComparingTo(original.getInterbankSettlementAmount());
        assertThat(rtn.getReturnedAmountCurrency()).isEqualTo(original.getInterbankSettlementCurrency());
    }

    @Test
    void sagaCompensationSetsCreationDateTime() {
        Pacs004Message rtn = Pacs004Message.forSagaCompensation(
                sampleTransfer(), "AM04", "Core rejected");

        assertThat(rtn.getCreationDateTime()).isNotNull();
    }

    @Test
    void sagaCompensationGeneratesUniqueMessageId() {
        Pacs008Message original = sampleTransfer();

        Pacs004Message rtn1 = Pacs004Message.forSagaCompensation(original, "AM04", "desc");
        Pacs004Message rtn2 = Pacs004Message.forSagaCompensation(original, "AM04", "desc");

        // Each return message must have a unique ID even for the same original
        assertThat(rtn1.getMessageId()).isNotEqualTo(rtn2.getMessageId());
    }

    @Test
    void sagaCompensationReturnIdContainsOriginalTransactionId() {
        Pacs008Message original = sampleTransfer();

        Pacs004Message rtn = Pacs004Message.forSagaCompensation(original, "AM04", "Core rejected");

        assertThat(rtn.getReturnId()).contains(original.getTransactionId());
    }

    // --- forSagaCompensation: agent role reversal ---

    @Test
    void sagaCompensationReversesAgentRoles() {
        // In the return, the original creditor agent becomes the returning agent,
        // and the original debtor agent becomes the receiving agent.
        // This ensures funds flow back to the originating institution.
        Pacs008Message original = sampleTransfer();

        Pacs004Message rtn = Pacs004Message.forSagaCompensation(original, "AM04", "Core rejected");

        assertThat(rtn.getReturningAgentRoutingNumber())
                .isEqualTo(original.getCreditorAgentRoutingNumber());
        assertThat(rtn.getReceivingAgentRoutingNumber())
                .isEqualTo(original.getDebtorAgentRoutingNumber());
    }

    @Test
    void agentRoutingNumbersAreNotSwappedToSameValue() {
        // Guard against a copy-paste bug where both routing numbers are set
        // to the same value
        Pacs004Message rtn = Pacs004Message.forSagaCompensation(
                sampleTransfer(), "AM04", "Core rejected");

        assertThat(rtn.getReturningAgentRoutingNumber())
                .isNotEqualTo(rtn.getReceivingAgentRoutingNumber());
    }

    // --- forSagaCompensation: reason codes ---

    @Test
    void sagaCompensationWithAM04ReasonCode() {
        Pacs004Message rtn = Pacs004Message.forSagaCompensation(
                sampleTransfer(), "AM04", "Insufficient funds on final posting");

        assertThat(rtn.getReturnReasonCode()).isEqualTo("AM04");
        assertThat(rtn.getReturnReasonDescription()).isEqualTo("Insufficient funds on final posting");
    }

    @Test
    void sagaCompensationWithAC04ReasonCode() {
        Pacs004Message rtn = Pacs004Message.forSagaCompensation(
                sampleTransfer(), "AC04", "Account closed");

        assertThat(rtn.getReturnReasonCode()).isEqualTo("AC04");
    }

    @Test
    void sagaCompensationWithNarrReasonCode() {
        String narrative = "Core system returned error code 9042: account flagged for review";

        Pacs004Message rtn = Pacs004Message.forSagaCompensation(sampleTransfer(), "NARR", narrative);

        assertThat(rtn.getReturnReasonCode()).isEqualTo("NARR");
        assertThat(rtn.getReturnReasonDescription()).isEqualTo(narrative);
    }

    // --- Helper ---

    private Pacs008Message sampleTransfer() {
        return Pacs008Message.builder()
                .messageId("MSG-ORIG-001")
                .transactionId("TXN-ORIG-001")
                .endToEndId("E2E-ORIG-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal("500.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("011000138")
                .debtorAccountNumber("987654321")
                .creditorAccountNumber("123456789")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .remittanceInformation("Invoice 5678")
                .build();
    }
}
