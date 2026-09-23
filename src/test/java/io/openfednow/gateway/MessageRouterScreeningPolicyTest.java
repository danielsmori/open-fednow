package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.SyncAsyncBridge;
import io.openfednow.events.PaymentEventPublisher;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.fraud.FraudScreeningPort;
import io.openfednow.processing.fraud.ScreeningResult;
import io.openfednow.processing.idempotency.IdempotencyService;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.shadowledger.AvailabilityBridge;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MessageRouterScreeningPolicyTest {
    private final FedNowClient client = mock(FedNowClient.class);
    private final ShadowLedger ledger = mock(ShadowLedger.class);
    private final SagaOrchestrator sagas = mock(SagaOrchestrator.class);
    private final SyncAsyncBridge core = mock(SyncAsyncBridge.class);
    private final AvailabilityBridge availability = mock(AvailabilityBridge.class);
    private final FraudScreeningPort screening = mock(FraudScreeningPort.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);

    private MessageRouter router(boolean failOpen) {
        when(idempotency.checkDuplicate(any())).thenReturn(Optional.empty());
        return new MessageRouter(client, mock(CoreBankingAdapter.class), idempotency,
                availability, core, new ObjectMapper(), ledger, sagas,
                mock(PaymentEventPublisher.class), screening, new SimpleMeterRegistry(),
                100L, false, failOpen);
    }

    private Pacs008Message message() {
        return Pacs008Message.builder().endToEndId("E2E-SCREEN").transactionId("TX-SCREEN")
                .interbankSettlementCurrency("USD").interbankSettlementAmount(new BigDecimal("10.00"))
                .debtorAccountNumber("SYNTHETIC-DEBTOR").creditorAccountNumber("SYNTHETIC-CREDITOR").build();
    }

    private void assertBlocked(MessageRouter router, boolean inbound, String reason) {
        var response = inbound ? router.routeInbound(message(), Rail.FEDNOW) : router.routeOutbound(message());
        assertThat(response.getBody().getRejectReasonCode()).isEqualTo(reason);
        verifyNoInteractions(client, ledger, sagas, core);
        verify(availability, never()).queueForCoreProcessing(any(), any());
    }

    @org.junit.jupiter.api.Test
    void outageMustNotReachFundsCheck() {
        when(screening.screen(any())).thenThrow(new IllegalStateException("Synthetic outage"));
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        assertThat(router(false).routeOutbound(message()).getBody().getRejectReasonCode()).isEqualTo("TS01");
        verifyNoInteractions(ledger, sagas, client);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void explicitBlockPreventsFinancialEffects(boolean inbound) {
        when(screening.screen(any())).thenReturn(ScreeningResult.block("FRAD", "Synthetic denylist"));
        assertBlocked(router(false), inbound, "FRAD");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serviceErrorFailsClosed(boolean inbound) {
        when(screening.screen(any())).thenThrow(new IllegalStateException("Synthetic outage"));
        assertBlocked(router(false), inbound, "TS01");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nullResultFailsClosed(boolean inbound) {
        assertBlocked(router(false), inbound, "TS01");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void timeoutFailsClosed(boolean inbound) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(screening.screen(any())).thenAnswer(invocation -> {
            release.await();
            return ScreeningResult.pass();
        });
        try {
            assertBlocked(router(false), inbound, "TS01");
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void explicitFailOpenContinuesToFundsCheckOnTimeoutOrError(boolean timeout) {
        CountDownLatch release = new CountDownLatch(1);
        when(screening.screen(any())).thenAnswer(invocation -> {
            if (timeout) release.await();
            throw new IllegalStateException("Synthetic outage");
        });
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        try {
            assertThat(router(true).routeOutbound(message()).getBody().getRejectReasonCode()).isEqualTo("AM04");
            verify(ledger).getAvailableBalance("SYNTHETIC-DEBTOR");
            verifyNoInteractions(client, sagas, core);
        } finally {
            release.countDown();
        }
    }
}
