package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.SyncAsyncBridge;
import io.openfednow.events.PaymentEventPublisher;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.fraud.FraudScreeningPort;
import io.openfednow.processing.idempotency.IdempotencyService;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.shadowledger.AvailabilityBridge;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.BeforeEach;
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
 * Verifies that MessageRouter rejects payments in a currency the rail cannot
 * settle with the ISO 20022 AM03 (NotAllowedCurrency) reason code, and that
 * the rejection happens before any downstream side effects (saga init, fraud
 * screen, shadow ledger).
 *
 * <p>The rejection path is short-circuited very early -- only IdempotencyService
 * and PaymentEventPublisher are exercised. Everything else is mocked out so the
 * test remains a unit test.
 */
class MessageRouterCurrencyGuardTest {

    private IdempotencyService idempotencyService;
    private PaymentEventPublisher eventPublisher;
    private SagaOrchestrator sagaOrchestrator;
    private ShadowLedger shadowLedger;
    private FraudScreeningPort fraudScreeningPort;
    private MessageRouter router;

    @BeforeEach
    void setUp() {
        idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.checkDuplicate(any())).thenReturn(Optional.empty());
        eventPublisher = mock(PaymentEventPublisher.class);
        sagaOrchestrator = mock(SagaOrchestrator.class);
        shadowLedger = mock(ShadowLedger.class);
        fraudScreeningPort = mock(FraudScreeningPort.class);

        router = new MessageRouter(
                mock(FedNowClient.class),
                mock(CoreBankingAdapter.class),
                idempotencyService,
                mock(AvailabilityBridge.class),
                mock(SyncAsyncBridge.class),
                new ObjectMapper(),
                shadowLedger,
                sagaOrchestrator,
                eventPublisher,
                fraudScreeningPort,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                1500L,
                true);
    }

    // ── Inbound ──────────────────────────────────────────────────────────────

    @Test
    void inboundInEurRejectedWithAm03OnFedNow() {
        Pacs008Message eur = messageWithCurrency("EUR");

        ResponseEntity<Pacs002Message> resp = router.routeInbound(eur, Rail.FEDNOW);

        Pacs002Message body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(body.getRejectReasonCode()).isEqualTo("AM03");
        assertThat(body.getRejectReasonDescription()).contains("EUR", "FEDNOW", "USD");
    }

    @Test
    void inboundInEurRejectedBeforeAnySideEffects() {
        Pacs008Message eur = messageWithCurrency("EUR");

        router.routeInbound(eur, Rail.FEDNOW);

        // Rejection must happen BEFORE saga init, fraud screen, and shadow ledger touch.
        verify(sagaOrchestrator, never()).initiate(any(), any());
        verify(fraudScreeningPort, never()).screen(any());
        verify(shadowLedger, never()).applyCredit(any(), any(), any());
        // The rejection MUST record an idempotent outcome so a retry sees the same RJCT.
        verify(idempotencyService, times(1)).recordOutcome(eq("E2E-CURR-TEST"), any());
    }

    @Test
    void inboundInUsdOnFedNowClearsCurrencyGuard() {
        // The guard must let a USD message through to the idempotency check.
        // We stub idempotency to return an ACSC cached response so we don't need
        // to mock the entire downstream pipeline.
        Pacs002Message cached = Pacs002Message.builder()
                .originalEndToEndId("E2E-CURR-TEST")
                .originalTransactionId("TXN-CURR-TEST")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.now())
                .build();
        when(idempotencyService.checkDuplicate("E2E-CURR-TEST")).thenReturn(Optional.of(cached));

        ResponseEntity<Pacs002Message> resp = router.routeInbound(validMessage(), Rail.FEDNOW);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    @Test
    void inboundInUsdOnRtpAlsoClears() {
        Pacs002Message cached = Pacs002Message.builder()
                .originalEndToEndId("E2E-CURR-TEST")
                .originalTransactionId("TXN-CURR-TEST")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.now())
                .build();
        when(idempotencyService.checkDuplicate("E2E-CURR-TEST")).thenReturn(Optional.of(cached));

        ResponseEntity<Pacs002Message> resp = router.routeInbound(validMessage(), Rail.RTP);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    // ── Outbound ─────────────────────────────────────────────────────────────

    @Test
    void outboundInGbpRejectedWithAm03() {
        Pacs008Message gbp = messageWithCurrency("GBP");

        ResponseEntity<Pacs002Message> resp = router.routeOutbound(gbp);

        Pacs002Message body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(body.getRejectReasonCode()).isEqualTo("AM03");
        assertThat(body.getRejectReasonDescription()).contains("GBP", "FEDNOW");
    }

    @Test
    void outboundInGbpRejectedBeforeShadowLedgerCheck() {
        Pacs008Message gbp = messageWithCurrency("GBP");

        router.routeOutbound(gbp);

        // No balance lookup, no saga, no fraud screen must have happened
        verify(shadowLedger, never()).getAvailableBalance(any());
        verify(sagaOrchestrator, never()).initiate(any(), any());
        verify(fraudScreeningPort, never()).screen(any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Pacs008Message validMessage() {
        return messageWithCurrency("USD");
    }

    private Pacs008Message messageWithCurrency(String currency) {
        return Pacs008Message.builder()
                .messageId("MSG-CURR-TEST")
                .creationDateTime(OffsetDateTime.parse("2026-05-19T10:00:00Z"))
                .numberOfTransactions(1)
                .endToEndId("E2E-CURR-TEST")
                .transactionId("TXN-CURR-TEST")
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency(currency)
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("111111111")
                .creditorAccountNumber("222222222")
                .debtorName("Alice")
                .creditorName("Bob")
                .build();
    }
}
