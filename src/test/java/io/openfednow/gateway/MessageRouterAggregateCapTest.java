package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.SyncAsyncBridge;
import io.openfednow.events.PaymentEventPublisher;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.fraud.FraudScreeningPort;
import io.openfednow.processing.fraud.ScreeningResult;
import io.openfednow.processing.idempotency.IdempotencyService;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.shadowledger.AvailabilityBridge;
import io.openfednow.shadowledger.ShadowLedger;
import io.openfednow.shadowledger.ShadowLedger.CappedDebitResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * Branching of the bridge-mode aggregate send cap in
 * {@link MessageRouter#routeOutbound} — issue #68, PR 2.
 *
 * <p>Truth table for "does this send count toward the aggregate window":
 * <pre>
 *   capEnabled  bridgeMode  windowOpen  →  debit path
 *   ──────────  ──────────  ──────────  ─  ──────────
 *   false       any         any         →  applyDebit (unchanged behavior)
 *   true        true        any         →  applyDebitCapped
 *   true        false       true        →  applyDebitCapped (cap holds until reconciliation)
 *   true        false       false       →  applyDebit
 * </pre>
 *
 * <p>Also pins the race-loser path: when the atomic debit loses to a
 * concurrent send after the pre-checks passed, the saga is compensated and the
 * caller gets a final AM04 — never an error or a FedNow submission.
 */
class MessageRouterAggregateCapTest {

    // ── Which debit path ─────────────────────────────────────────────────────

    @Test
    void capDisabledKeepsThePlainDebitPath() {
        Fixture f = new Fixture(false, true, true);

        f.router.routeOutbound(message("E2E-OFF"));

        verify(f.shadowLedger).applyDebit(anyString(), any(), anyString());
        verify(f.shadowLedger, never()).applyDebitCapped(anyString(), any(), anyString());
        verify(f.shadowLedger, never()).hasOpenAggregate(anyString());
    }

    @Test
    void bridgeModeSendIsCounted() {
        Fixture f = new Fixture(true, true, false);

        f.router.routeOutbound(message("E2E-BRIDGE"));

        verify(f.shadowLedger).applyDebitCapped(anyString(), any(), anyString());
        verify(f.shadowLedger, never()).applyDebit(anyString(), any(), anyString());
    }

    @Test
    void onlineSendWithAnOpenWindowIsStillCounted() {
        Fixture f = new Fixture(true, false, true);

        f.router.routeOutbound(message("E2E-WINDOW"));

        verify(f.shadowLedger).applyDebitCapped(anyString(), any(), anyString());
        verify(f.shadowLedger, never()).applyDebit(anyString(), any(), anyString());
    }

    @Test
    void onlineSendWithoutAWindowIsNotCounted() {
        Fixture f = new Fixture(true, false, false);

        f.router.routeOutbound(message("E2E-ONLINE"));

        verify(f.shadowLedger).applyDebit(anyString(), any(), anyString());
        verify(f.shadowLedger, never()).applyDebitCapped(anyString(), any(), anyString());
    }

    // ── Pre-check ────────────────────────────────────────────────────────────

    @Test
    void preCheckRejectsBeforeAnySagaOrDebit() {
        Fixture f = new Fixture(true, true, false);
        when(f.shadowLedger.wouldExceedAggregateCap(anyString(), any())).thenReturn(true);

        Pacs002Message r = f.router.routeOutbound(message("E2E-PRE")).getBody();

        assertThat(r.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(r.getRejectReasonCode()).isEqualTo("AM04");
        verify(f.sagaOrchestrator, never()).initiate(any(), any());
        verify(f.shadowLedger, never()).applyDebitCapped(anyString(), any(), anyString());
        verify(f.fedNowClient, never()).submitCreditTransfer(any());
        verify(f.idempotencyService).recordOutcome(eq("E2E-PRE"), any());
        assertThat(f.capExceeded()).isEqualTo(1.0);
    }

    // ── Race losers ──────────────────────────────────────────────────────────

    @Test
    void raceLoserOnTheCapIsCompensatedWithAm04() {
        Fixture f = new Fixture(true, true, false);
        when(f.shadowLedger.applyDebitCapped(anyString(), any(), anyString()))
                .thenReturn(CappedDebitResult.AGGREGATE_CAP_EXCEEDED);

        Pacs002Message r = f.router.routeOutbound(message("E2E-RACE-CAP")).getBody();

        assertThat(r.getRejectReasonCode()).isEqualTo("AM04");
        verify(f.sagaOrchestrator).compensate("SAGA-1", "AM04");
        verify(f.sagaOrchestrator, never()).advance(any(), eq(PaymentSaga.SagaState.FUNDS_RESERVED));
        verify(f.fedNowClient, never()).submitCreditTransfer(any());
        verify(f.idempotencyService).recordOutcome(eq("E2E-RACE-CAP"), any());
        assertThat(f.capExceeded()).isEqualTo(1.0);
    }

    @Test
    void raceLoserOnTheBalanceIsCompensatedWithInsufficientFunds() {
        Fixture f = new Fixture(true, true, false);
        when(f.shadowLedger.applyDebitCapped(anyString(), any(), anyString()))
                .thenReturn(CappedDebitResult.INSUFFICIENT_FUNDS);

        Pacs002Message r = f.router.routeOutbound(message("E2E-RACE-NSF")).getBody();

        assertThat(r.getRejectReasonCode()).isEqualTo("AM04");
        assertThat(r.getRejectReasonDescription()).contains("Insufficient funds");
        verify(f.sagaOrchestrator).compensate("SAGA-1", "AM04");
        verify(f.fedNowClient, never()).submitCreditTransfer(any());
        // a balance race is not a cap rejection
        assertThat(f.capExceeded()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Pacs008Message message(String e2e) {
        return Pacs008Message.builder()
                .messageId("MSG-" + e2e)
                .creationDateTime(OffsetDateTime.parse("2026-11-13T10:00:00Z"))
                .numberOfTransactions(1)
                .endToEndId(e2e)
                .transactionId("TXN-" + e2e)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("111111111")
                .creditorAccountNumber("222222222")
                .debtorName("Alice")
                .creditorName("Bob")
                .build();
    }

    private static class Fixture {
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final IdempotencyService idempotencyService = mock(IdempotencyService.class);
        final AvailabilityBridge availabilityBridge = mock(AvailabilityBridge.class);
        final ShadowLedger shadowLedger = mock(ShadowLedger.class);
        final SagaOrchestrator sagaOrchestrator = mock(SagaOrchestrator.class);
        final FraudScreeningPort fraudScreeningPort = mock(FraudScreeningPort.class);
        final FedNowClient fedNowClient = mock(FedNowClient.class);
        final MessageRouter router;

        Fixture(boolean capEnabled, boolean inBridgeMode, boolean windowOpen) {
            when(idempotencyService.checkDuplicate(any())).thenReturn(Optional.empty());
            when(sagaOrchestrator.findByEndToEndId(any())).thenReturn(Optional.empty());
            when(availabilityBridge.isInBridgeMode()).thenReturn(inBridgeMode);
            when(fraudScreeningPort.screen(any())).thenReturn(ScreeningResult.pass());
            when(shadowLedger.getAvailableBalance(anyString())).thenReturn(new BigDecimal("10000.00"));
            when(shadowLedger.isAggregateCapEnabled()).thenReturn(capEnabled);
            when(shadowLedger.hasOpenAggregate(anyString())).thenReturn(windowOpen);
            when(shadowLedger.applyDebitCapped(anyString(), any(), anyString()))
                    .thenReturn(CappedDebitResult.APPLIED);
            when(sagaOrchestrator.initiate(any(), any()))
                    .thenReturn(new PaymentSaga("SAGA-1", "TXN", Rail.FEDNOW));
            router = new MessageRouter(
                    fedNowClient,
                    mock(CoreBankingAdapter.class),
                    idempotencyService,
                    availabilityBridge,
                    mock(SyncAsyncBridge.class),
                    new ObjectMapper(),
                    shadowLedger,
                    sagaOrchestrator,
                    mock(PaymentEventPublisher.class),
                    fraudScreeningPort,
                    meterRegistry,
                    1500L,
                    true, false);
        }

        double capExceeded() {
            return meterRegistry.counter(MessageRouter.AGGREGATE_CAP_EXCEEDED_METRIC).count();
        }
    }
}
