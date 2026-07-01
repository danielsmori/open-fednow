package io.openfednow.processing.saga;

import io.openfednow.gateway.CorrelationFilter;
import io.openfednow.gateway.Rail;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for the request_id propagation added in issue #21.
 *
 * <p>{@code initiate()} must snapshot {@code MDC[requestId]} and pass it as the
 * sixth INSERT parameter, so that an admin_audit_log entry can be joined to the
 * saga it triggered. When no MDC value is present (e.g., scheduled bridge
 * replays), the parameter must be {@code null}.
 */
class SagaOrchestratorInitiateTest {

    private JdbcTemplate jdbc;
    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        orchestrator = new SagaOrchestrator(jdbc, mock(ShadowLedger.class));
    }

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationFilter.MDC_REQUEST_ID);
    }

    @Test
    void initiateStampsRequestIdFromMdcOntoSagaRow() {
        MDC.put(CorrelationFilter.MDC_REQUEST_ID, "req-corr-42");

        orchestrator.initiate(inbound(), Rail.FEDNOW);

        Object[] params = capturedInsertParams();
        // (saga_id, transaction_id, end_to_end_id, state, source_rail, request_id)
        assertThat(params[5]).isEqualTo("req-corr-42");
    }

    @Test
    void initiateWithoutMdcRequestIdWritesNull() {
        // MDC left empty — scheduled sagas (SagaTimeoutMonitor replay,
        // AvailabilityBridge, etc.) run outside any HTTP request.
        orchestrator.initiate(inbound(), Rail.FEDNOW);

        Object[] params = capturedInsertParams();
        assertThat(params[5]).isNull();
    }

    private Object[] capturedInsertParams() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(),
                captor.capture(), captor.capture(), captor.capture(),
                captor.capture(), captor.capture(), captor.capture());
        return captor.getAllValues().toArray();
    }

    private Pacs008Message inbound() {
        return Pacs008Message.builder()
                .messageId("MSG-TEST")
                .creationDateTime(OffsetDateTime.parse("2026-05-19T10:00:00Z"))
                .endToEndId("E2E-TEST")
                .transactionId("TXN-TEST")
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorName("Alice")
                .debtorAccountNumber("999888")
                .debtorAgentRoutingNumber("021000021")
                .creditorName("Bob")
                .creditorAccountNumber("123456")
                .creditorAgentRoutingNumber("026009593")
                .build();
    }
}
