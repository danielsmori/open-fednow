package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.SyncAsyncBridge;
import io.openfednow.events.PaymentEventPublisher;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.fraud.FraudScreeningPort;
import io.openfednow.processing.fraud.ScreeningResult;
import io.openfednow.processing.idempotency.IdempotencyService;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.shadowledger.AvailabilityBridge;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Characterizes an OPEN defect; passing this test does not certify safe settlement. */
@WireMockTest
class UncertainSubmissionReproducerTest {
    @Test
    void delayedAcceptanceIsCurrentlyMisclassifiedAsRejection(WireMockRuntimeInfo server) {
        stubFor(post(urlEqualTo("/transfers")).willReturn(aResponse()
                .withFixedDelay(1500).withHeader("Content-Type", "application/json")
                .withBody("{\"transactionStatus\":\"ACSC\"}")));
        var ledger = mock(ShadowLedger.class);
        var sagas = mock(SagaOrchestrator.class);
        var idempotency = mock(IdempotencyService.class);
        var screening = mock(FraudScreeningPort.class);
        when(ledger.getAvailableBalance(any())).thenReturn(new BigDecimal("100.00"));
        when(idempotency.checkDuplicate(any())).thenReturn(Optional.empty());
        when(screening.screen(any())).thenReturn(ScreeningResult.pass());
        when(sagas.initiate(any(), eq(Rail.FEDNOW)))
                .thenReturn(new PaymentSaga("SAGA-UNCERTAIN", "TX-UNCERTAIN", Rail.FEDNOW));
        var router = new MessageRouter(new HttpFedNowClient(server.getHttpBaseUrl(), 1),
                mock(CoreBankingAdapter.class), idempotency, mock(AvailabilityBridge.class),
                mock(SyncAsyncBridge.class), new ObjectMapper(), ledger, sagas,
                mock(PaymentEventPublisher.class), screening, new SimpleMeterRegistry(),
                1500, false, false);
        var message = Pacs008Message.builder().endToEndId("E2E-UNCERTAIN").transactionId("TX-UNCERTAIN")
                .interbankSettlementCurrency("USD").interbankSettlementAmount(new BigDecimal("10.00"))
                .debtorAccountNumber("SYNTHETIC-DEBTOR").build();

        var response = router.routeOutbound(message);

        // The server received the request, but the caller missed its delayed success.
        com.github.tomakehurst.wiremock.client.WireMock.verify(1, postRequestedFor(urlEqualTo("/transfers")));
        assertThat(response.getBody().getRejectReasonCode()).isEqualTo("NARR");
        org.mockito.Mockito.verify(ledger).applyDebit("SYNTHETIC-DEBTOR", new BigDecimal("10.00"), "TX-UNCERTAIN");
        org.mockito.Mockito.verify(sagas).compensate("SAGA-UNCERTAIN", "NARR");
        // These assertions expose existing compensation, not the desired behavior.
        // WireMock simulates delayed acceptance; no real rail settlement occurs.
    }
}
