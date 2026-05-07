package io.openfednow.iso20022;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static io.openfednow.iso20022.Camt029Message.ResolutionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Camt029Message}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Builder populates all fields correctly</li>
 *   <li>{@code cancelled()} factory returns CNCL with no rejection fields</li>
 *   <li>{@code rejected()} factory returns RJCR with reason code and description</li>
 *   <li>{@code pending()} factory returns PDCR with no rejection fields</li>
 *   <li>All factory methods derive references from the original camt.056</li>
 *   <li>Unique message IDs are generated for each call</li>
 *   <li>Common rejection reason codes (ARDT, NOAS, LEGL, NARR) are supported</li>
 * </ul>
 */
class Camt029MessageTest {

    // --- Builder ---

    @Test
    void builderSetsAllFields() {
        OffsetDateTime now = OffsetDateTime.now();

        Camt029Message msg = Camt029Message.builder()
                .messageId("RES-001")
                .creationDateTime(now)
                .caseId("CASE-TXN-001")
                .originalCancellationMessageId("CNCL-001")
                .originalEndToEndId("E2E-001")
                .originalTransactionId("TXN-001")
                .resolutionStatus(RJCR)
                .rejectionReasonCode("ARDT")
                .rejectionReasonDescription("Payment settled before cancellation arrived")
                .build();

        assertThat(msg.getMessageId()).isEqualTo("RES-001");
        assertThat(msg.getCreationDateTime()).isEqualTo(now);
        assertThat(msg.getCaseId()).isEqualTo("CASE-TXN-001");
        assertThat(msg.getOriginalCancellationMessageId()).isEqualTo("CNCL-001");
        assertThat(msg.getOriginalEndToEndId()).isEqualTo("E2E-001");
        assertThat(msg.getOriginalTransactionId()).isEqualTo("TXN-001");
        assertThat(msg.getResolutionStatus()).isEqualTo(RJCR);
        assertThat(msg.getRejectionReasonCode()).isEqualTo("ARDT");
        assertThat(msg.getRejectionReasonDescription()).contains("settled");
    }

    // --- cancelled() factory ---

    @Test
    void cancelledFactorySetsCnclStatus() {
        Camt029Message res = Camt029Message.cancelled(sampleCancellationRequest());

        assertThat(res.getResolutionStatus()).isEqualTo(CNCL);
    }

    @Test
    void cancelledFactoryDerivesReferencesFromCamt056() {
        Camt056Message request = sampleCancellationRequest();

        Camt029Message res = Camt029Message.cancelled(request);

        assertThat(res.getCaseId()).isEqualTo(request.getCaseId());
        assertThat(res.getOriginalCancellationMessageId()).isEqualTo(request.getMessageId());
        assertThat(res.getOriginalEndToEndId()).isEqualTo(request.getOriginalEndToEndId());
        assertThat(res.getOriginalTransactionId()).isEqualTo(request.getOriginalTransactionId());
    }

    @Test
    void cancelledFactoryHasNoRejectionFields() {
        Camt029Message res = Camt029Message.cancelled(sampleCancellationRequest());

        assertThat(res.getRejectionReasonCode()).isNull();
        assertThat(res.getRejectionReasonDescription()).isNull();
    }

    @Test
    void cancelledFactorySetsCreationDateTime() {
        Camt029Message res = Camt029Message.cancelled(sampleCancellationRequest());

        assertThat(res.getCreationDateTime()).isNotNull();
    }

    // --- rejected() factory ---

    @Test
    void rejectedFactorySetsRjcrStatus() {
        Camt029Message res = Camt029Message.rejected(
                sampleCancellationRequest(), "ARDT", "Payment already settled");

        assertThat(res.getResolutionStatus()).isEqualTo(RJCR);
    }

    @Test
    void rejectedFactoryCarriesRejectionReasonCode() {
        Camt029Message res = Camt029Message.rejected(
                sampleCancellationRequest(), "ARDT", "Payment already settled");

        assertThat(res.getRejectionReasonCode()).isEqualTo("ARDT");
        assertThat(res.getRejectionReasonDescription()).isEqualTo("Payment already settled");
    }

    @Test
    void rejectedFactoryDerivesReferencesFromCamt056() {
        Camt056Message request = sampleCancellationRequest();

        Camt029Message res = Camt029Message.rejected(request, "NOAS", "No response from agent");

        assertThat(res.getCaseId()).isEqualTo(request.getCaseId());
        assertThat(res.getOriginalCancellationMessageId()).isEqualTo(request.getMessageId());
        assertThat(res.getOriginalEndToEndId()).isEqualTo(request.getOriginalEndToEndId());
        assertThat(res.getOriginalTransactionId()).isEqualTo(request.getOriginalTransactionId());
    }

    // --- pending() factory ---

    @Test
    void pendingFactorySetsPdcrStatus() {
        Camt029Message res = Camt029Message.pending(sampleCancellationRequest());

        assertThat(res.getResolutionStatus()).isEqualTo(PDCR);
    }

    @Test
    void pendingFactoryHasNoRejectionFields() {
        Camt029Message res = Camt029Message.pending(sampleCancellationRequest());

        assertThat(res.getRejectionReasonCode()).isNull();
        assertThat(res.getRejectionReasonDescription()).isNull();
    }

    // --- Unique IDs ---

    @Test
    void eachFactoryCallGeneratesUniqueMessageId() {
        Camt056Message request = sampleCancellationRequest();

        Camt029Message res1 = Camt029Message.cancelled(request);
        Camt029Message res2 = Camt029Message.cancelled(request);

        assertThat(res1.getMessageId()).isNotEqualTo(res2.getMessageId());
    }

    // --- Common RJCR rejection reason codes ---

    @Test
    void rejectionWithArdtReasonCode() {
        Camt029Message res = Camt029Message.rejected(
                sampleCancellationRequest(), "ARDT", "Already returned or transferred");

        assertThat(res.getRejectionReasonCode()).isEqualTo("ARDT");
    }

    @Test
    void rejectionWithNoasReasonCode() {
        Camt029Message res = Camt029Message.rejected(
                sampleCancellationRequest(), "NOAS", "No answer from next agent");

        assertThat(res.getRejectionReasonCode()).isEqualTo("NOAS");
    }

    @Test
    void rejectionWithLeglReasonCode() {
        Camt029Message res = Camt029Message.rejected(
                sampleCancellationRequest(), "LEGL", "Legal hold prevents cancellation");

        assertThat(res.getRejectionReasonCode()).isEqualTo("LEGL");
    }

    @Test
    void rejectionWithNarrReasonCode() {
        String narrative = "Payment subject to regulatory freeze order 2024-FRZ-0042";

        Camt029Message res = Camt029Message.rejected(sampleCancellationRequest(), "NARR", narrative);

        assertThat(res.getRejectionReasonCode()).isEqualTo("NARR");
        assertThat(res.getRejectionReasonDescription()).isEqualTo(narrative);
    }

    // --- Helpers ---

    private Camt056Message sampleCancellationRequest() {
        return Camt056Message.forPaymentCancellation(sampleTransfer(), "DUPL", null);
    }

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
