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
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.shadowledger.AvailabilityBridge;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code openfednow.bridge-mode.allow-sends} guard added in #66.
 *
 * <p>Truth table:
 * <pre>
 *   bridgeMode  allowSends  →  result
 *   ──────────  ──────────  ─  ──────
 *   false       true        →  proceed  (normal online-core path)
 *   false       false       →  proceed  (flag only kicks in during bridge mode)
 *   true        true        →  proceed  (opted into send-during-bridge behaviour)
 *   true        false       →  reject with TS01 + metric
 * </pre>
 *
 * <p>The rejection also has to be idempotent-cached and event-published, matching
 * the treatment of the other pre-side-effect rejections in {@code routeOutbound}.
 */
class MessageRouterBridgeSendsGuardTest {

    // ── flag on, bridge on → send proceeds ───────────────────────────────────

    @Test
    void bridgeModeSendAllowedWhenFlagOn() {
        Fixture f = new Fixture(true, true);
        // Bridge mode uses the existing "proceed" path; short-circuit via the
        // idempotency cache so we don't have to mock the full FedNow submission.
        Pacs002Message cachedAcsc = accepted();
        when(f.idempotencyService.checkDuplicate("E2E-GUARD-1"))
                .thenReturn(Optional.of(cachedAcsc));

        ResponseEntity<Pacs002Message> resp = f.router.routeOutbound(message("E2E-GUARD-1"));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        assertThat(f.meterRegistry.counter(MessageRouter.BRIDGE_SENDS_BLOCKED_METRIC).count()).isZero();
    }

    // ── flag off, bridge off → send proceeds (flag is scoped to bridge mode) ─

    @Test
    void onlineCoreSendProceedsRegardlessOfFlag() {
        Fixture f = new Fixture(false, false);
        Pacs002Message cachedAcsc = accepted();
        when(f.idempotencyService.checkDuplicate("E2E-GUARD-2"))
                .thenReturn(Optional.of(cachedAcsc));

        ResponseEntity<Pacs002Message> resp = f.router.routeOutbound(message("E2E-GUARD-2"));

        assertThat(resp.getBody().getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        assertThat(f.meterRegistry.counter(MessageRouter.BRIDGE_SENDS_BLOCKED_METRIC).count()).isZero();
    }

    // ── flag off, bridge on → REJECT with TS01 ───────────────────────────────

    @Test
    void bridgeModeSendRejectedWithTs01WhenFlagOff() {
        Fixture f = new Fixture(false, true);

        ResponseEntity<Pacs002Message> resp = f.router.routeOutbound(message("E2E-GUARD-3"));

        Pacs002Message body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(body.getRejectReasonCode()).isEqualTo("TS01");
        assertThat(body.getRejectReasonDescription()).contains("bridge-mode", "maintenance");
    }

    @Test
    void bridgeModeRejectionIncrementsBlockedCounter() {
        Fixture f = new Fixture(false, true);

        f.router.routeOutbound(message("E2E-METRIC-A"));
        f.router.routeOutbound(message("E2E-METRIC-B"));

        assertThat(f.meterRegistry.counter(MessageRouter.BRIDGE_SENDS_BLOCKED_METRIC).count())
                .isEqualTo(2.0);
    }

    @Test
    void bridgeModeRejectionShortCircuitsBeforeAnySideEffects() {
        Fixture f = new Fixture(false, true);

        f.router.routeOutbound(message("E2E-SHORT"));

        // The guard must fire BEFORE fraud screening, funds check, saga init.
        verify(f.fraudScreeningPort, never()).screen(any());
        verify(f.shadowLedger, never()).getAvailableBalance(any());
        verify(f.sagaOrchestrator, never()).initiate(any(), any());
        // But the rejection MUST be recorded idempotently so a retry sees the
        // same TS01 rather than being re-evaluated once bridge mode clears.
        verify(f.idempotencyService, times(1)).recordOutcome(eq("E2E-SHORT"), any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Pacs008Message message(String e2e) {
        return Pacs008Message.builder()
                .messageId("MSG-" + e2e)
                .creationDateTime(OffsetDateTime.parse("2026-07-15T10:00:00Z"))
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

    private static Pacs002Message accepted() {
        return Pacs002Message.builder()
                .originalEndToEndId("cached")
                .originalTransactionId("cached")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    /** Assembles a {@link MessageRouter} with the two guard-relevant inputs configurable. */
    private static class Fixture {
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final IdempotencyService idempotencyService = mock(IdempotencyService.class);
        final AvailabilityBridge availabilityBridge = mock(AvailabilityBridge.class);
        final ShadowLedger shadowLedger = mock(ShadowLedger.class);
        final SagaOrchestrator sagaOrchestrator = mock(SagaOrchestrator.class);
        final FraudScreeningPort fraudScreeningPort = mock(FraudScreeningPort.class);
        final PaymentEventPublisher eventPublisher = mock(PaymentEventPublisher.class);
        final MessageRouter router;

        Fixture(boolean bridgeSendsAllowed, boolean inBridgeMode) {
            when(idempotencyService.checkDuplicate(any())).thenReturn(Optional.empty());
            when(availabilityBridge.isInBridgeMode()).thenReturn(inBridgeMode);
            when(fraudScreeningPort.screen(any())).thenReturn(ScreeningResult.pass());
            router = new MessageRouter(
                    mock(FedNowClient.class),
                    mock(CoreBankingAdapter.class),
                    idempotencyService,
                    availabilityBridge,
                    mock(SyncAsyncBridge.class),
                    new ObjectMapper(),
                    shadowLedger,
                    sagaOrchestrator,
                    eventPublisher,
                    fraudScreeningPort,
                    meterRegistry,
                    1500L,
                    bridgeSendsAllowed);
        }
    }
}
