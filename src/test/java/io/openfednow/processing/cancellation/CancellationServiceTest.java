package io.openfednow.processing.cancellation;

import io.openfednow.gateway.Rail;
import io.openfednow.iso20022.Camt029Message;
import io.openfednow.iso20022.Camt056Message;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.processing.saga.SagaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CancellationService} — issue #26.
 *
 * <p>One test per branch of the decision matrix in ADR-0007. The matrix is small
 * and deterministic, so the tests double as the truth table that documents the
 * expected behavior.
 */
class CancellationServiceTest {

    private SagaOrchestrator orchestrator;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private CancellationService service;

    @BeforeEach
    void setUp() {
        orchestrator = mock(SagaOrchestrator.class);
        jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        // default: the original payment is a recorded inbound credit
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("CREDIT"));
        service = new CancellationService(orchestrator, jdbc);
    }

    // ── No saga found ────────────────────────────────────────────────────────

    @Test
    void unknownTransactionResolvesToRjcrNoOriginal() {
        when(orchestrator.findByTransactionId("TXN-MISSING")).thenReturn(Optional.empty());

        Camt029Message response = service.handleCancellationRequest(request("TXN-MISSING"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.RJCR);
        assertThat(response.getRejectionReasonCode()).isEqualTo(CancellationService.RJCR_NO_ORIGINAL);
        verify(orchestrator, never()).cancelInboundSaga(any(), any());
    }

    // ── Direction guard (issue #68 bug bash) ─────────────────────────────────

    @Test
    void outboundTransferIsRejectedNotCancelled() {
        // a camt.056 naming one of OUR outbound sends: cancelling it would mark
        // the saga FAILED, reverse the debit and free its cap slot while the
        // submission thread still sends the payment
        stubSaga("SAGA-OUT", PaymentSaga.SagaState.FUNDS_RESERVED);
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("DEBIT"));

        Camt029Message response = service.handleCancellationRequest(request("TXN-OUT"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.RJCR);
        verify(orchestrator, never()).cancelInboundSaga(any(), any());
    }

    @Test
    void sagaWithoutALedgerLegYetResolvesToPendingNotCancelled() {
        // mid-flight in either direction: cancelling now would race the payment thread
        stubSaga("SAGA-MIDFLIGHT", PaymentSaga.SagaState.INITIATED);
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of());

        Camt029Message response = service.handleCancellationRequest(request("TXN-MIDFLIGHT"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.PDCR);
        verify(orchestrator, never()).cancelInboundSaga(any(), any());
    }

    // ── Cancellable states (CNCL) ────────────────────────────────────────────

    @Test
    void initiatedStateIsCancelled() {
        stubSaga("SAGA-INIT", PaymentSaga.SagaState.INITIATED);

        Camt029Message response = service.handleCancellationRequest(request("TXN-INIT"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.CNCL);
        verify(orchestrator).cancelInboundSaga(eq("SAGA-INIT"), eq("DUPL"));
    }

    @Test
    void fundsReservedStateIsCancelled() {
        stubSaga("SAGA-FUNDS", PaymentSaga.SagaState.FUNDS_RESERVED);

        Camt029Message response = service.handleCancellationRequest(request("TXN-FUNDS"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.CNCL);
        verify(orchestrator).cancelInboundSaga(eq("SAGA-FUNDS"), eq("DUPL"));
    }

    // ── Pending states (PDCR) ────────────────────────────────────────────────

    @Test
    void coreSubmittedStateResolvesToPending() {
        stubSaga("SAGA-CORE", PaymentSaga.SagaState.CORE_SUBMITTED);

        Camt029Message response = service.handleCancellationRequest(request("TXN-CORE"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.PDCR);
        assertThat(response.getRejectionReasonCode()).isNull();
        verify(orchestrator, never()).cancelInboundSaga(any(), any());
    }

    @Test
    void compensatingStateResolvesToPending() {
        stubSaga("SAGA-COMP", PaymentSaga.SagaState.COMPENSATING);

        Camt029Message response = service.handleCancellationRequest(request("TXN-COMP"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.PDCR);
        verify(orchestrator, never()).cancelInboundSaga(any(), any());
    }

    // ── Already-settled states (RJCR / ARDT) ─────────────────────────────────

    @Test
    void fednowConfirmedStateIsRejectedAsAlreadySettled() {
        stubSaga("SAGA-CONF", PaymentSaga.SagaState.FEDNOW_CONFIRMED);

        Camt029Message response = service.handleCancellationRequest(request("TXN-CONF"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.RJCR);
        assertThat(response.getRejectionReasonCode()).isEqualTo(CancellationService.RJCR_ALREADY_SETTLED);
        assertThat(response.getRejectionReasonDescription()).contains("pacs.004");
        verify(orchestrator, never()).cancelInboundSaga(any(), any());
    }

    @Test
    void completedStateIsRejectedAsAlreadySettled() {
        stubSaga("SAGA-DONE", PaymentSaga.SagaState.COMPLETED);

        Camt029Message response = service.handleCancellationRequest(request("TXN-DONE"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.RJCR);
        assertThat(response.getRejectionReasonCode()).isEqualTo(CancellationService.RJCR_ALREADY_SETTLED);
    }

    // ── Terminal failure (RJCR / NOOR) ───────────────────────────────────────

    @Test
    void failedStateIsRejectedAsNoOriginal() {
        stubSaga("SAGA-FAIL", PaymentSaga.SagaState.FAILED);

        Camt029Message response = service.handleCancellationRequest(request("TXN-FAIL"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.RJCR);
        assertThat(response.getRejectionReasonCode()).isEqualTo(CancellationService.RJCR_NO_ORIGINAL);
    }

    // ── Matrix completeness sanity check ─────────────────────────────────────

    @ParameterizedTest
    @EnumSource(PaymentSaga.SagaState.class)
    void everySagaStateProducesADeterministicOutcome(PaymentSaga.SagaState state) {
        stubSaga("SAGA-MAT-" + state.name(), state);

        Camt029Message response = service.handleCancellationRequest(request("TXN-MAT-" + state.name()));

        // Every state must produce one of the three valid resolution statuses
        assertThat(response.getResolutionStatus())
                .isIn(Camt029Message.ResolutionStatus.CNCL,
                      Camt029Message.ResolutionStatus.RJCR,
                      Camt029Message.ResolutionStatus.PDCR);
    }

    // ── Response correlation fields ──────────────────────────────────────────

    @Test
    void responsePreservesCaseIdAndOriginalIdentifiers() {
        stubSaga("SAGA-CORR", PaymentSaga.SagaState.INITIATED);
        Camt056Message req = request("TXN-CORR");

        Camt029Message response = service.handleCancellationRequest(req);

        assertThat(response.getCaseId()).isEqualTo(req.getCaseId());
        assertThat(response.getOriginalCancellationMessageId()).isEqualTo(req.getMessageId());
        assertThat(response.getOriginalEndToEndId()).isEqualTo(req.getOriginalEndToEndId());
        assertThat(response.getOriginalTransactionId()).isEqualTo(req.getOriginalTransactionId());
    }

    @Test
    void cancellationReasonCodeFromRequestIsPropagatedToSaga() {
        stubSaga("SAGA-REASON", PaymentSaga.SagaState.FUNDS_RESERVED);
        Camt056Message req = request("TXN-REASON");
        req.setCancellationReasonCode("FRAD");

        service.handleCancellationRequest(req);

        verify(orchestrator).cancelInboundSaga(eq("SAGA-REASON"), eq("FRAD"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubSaga(String sagaId, PaymentSaga.SagaState state) {
        SagaSnapshot snapshot = new SagaSnapshot(
                sagaId, deriveTxnId(sagaId), "E2E-" + sagaId,
                state, Rail.FEDNOW, null, null,
                Instant.parse("2026-05-19T10:00:00Z"),
                Instant.parse("2026-05-19T10:00:05Z"),
                null);
        when(orchestrator.findByTransactionId(snapshot.transactionId())).thenReturn(Optional.of(snapshot));
    }

    private static String deriveTxnId(String sagaId) {
        return sagaId.replace("SAGA-", "TXN-");
    }

    private Camt056Message request(String originalTransactionId) {
        return Camt056Message.builder()
                .messageId("CNCL-" + originalTransactionId)
                .creationDateTime(OffsetDateTime.parse("2026-05-19T10:30:00Z"))
                .caseId("CASE-" + originalTransactionId)
                .originalMessageId("MSG-" + originalTransactionId)
                .originalEndToEndId("E2E-" + originalTransactionId.replace("TXN-", "SAGA-"))
                .originalTransactionId(originalTransactionId)
                .originalInterbankSettlementAmount(new BigDecimal("100.00"))
                .originalInterbankSettlementCurrency("USD")
                .cancellationReasonCode("DUPL")
                .cancellationReasonDescription("Duplicate")
                .build();
    }
}
