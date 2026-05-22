package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.processing.saga.SagaSnapshot;
import io.openfednow.shadowledger.AccountBalanceView;
import io.openfednow.shadowledger.ReconciliationService;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for the {@link AdminController} GET endpoints — issue #42.
 *
 * <p>{@code MockMvc.standaloneSetup} keeps these tests fast and free of the
 * Spring Security filter chain — auth behavior is covered separately by
 * {@code AdminEndpointSecurityTest}. Here we only verify the controller's
 * contract: that it calls the right service method and returns the right
 * JSON / status code.
 */
class AdminControllerTest {

    private MockMvc mockMvc;
    private SagaOrchestrator sagaOrchestrator;
    private ShadowLedger shadowLedger;

    @BeforeEach
    void setUp() {
        sagaOrchestrator = mock(SagaOrchestrator.class);
        shadowLedger = mock(ShadowLedger.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);

        AdminController controller = new AdminController(
                reconciliationService, sagaOrchestrator, shadowLedger);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    // ── GET /admin/sagas ──────────────────────────────────────────────────────

    @Test
    void listInflightSagas_returnsEmptyArrayWhenNoneActive() throws Exception {
        when(sagaOrchestrator.listInflight()).thenReturn(List.of());

        mockMvc.perform(get("/admin/sagas"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void listInflightSagas_returnsAllNonTerminalSagas() throws Exception {
        SagaSnapshot snapshot = sampleSnapshot("SAGA-A", "TXN-A", "E2E-A",
                PaymentSaga.SagaState.FUNDS_RESERVED);
        when(sagaOrchestrator.listInflight()).thenReturn(List.of(snapshot));

        mockMvc.perform(get("/admin/sagas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sagaId").value("SAGA-A"))
                .andExpect(jsonPath("$[0].transactionId").value("TXN-A"))
                .andExpect(jsonPath("$[0].state").value("FUNDS_RESERVED"))
                .andExpect(jsonPath("$[0].sourceRail").value("FEDNOW"));
    }

    @Test
    void listInflightSagas_delegatesToOrchestrator() throws Exception {
        when(sagaOrchestrator.listInflight()).thenReturn(List.of());

        mockMvc.perform(get("/admin/sagas")).andExpect(status().isOk());

        verify(sagaOrchestrator).listInflight();
    }

    // ── GET /admin/sagas/{transactionId} ──────────────────────────────────────

    @Test
    void getSagaByTransactionId_returns200WithSnapshotWhenFound() throws Exception {
        SagaSnapshot snapshot = sampleSnapshot("SAGA-B", "TXN-B", "E2E-B",
                PaymentSaga.SagaState.CORE_SUBMITTED);
        when(sagaOrchestrator.findByTransactionId("TXN-B")).thenReturn(Optional.of(snapshot));

        mockMvc.perform(get("/admin/sagas/TXN-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaId").value("SAGA-B"))
                .andExpect(jsonPath("$.transactionId").value("TXN-B"))
                .andExpect(jsonPath("$.endToEndId").value("E2E-B"))
                .andExpect(jsonPath("$.state").value("CORE_SUBMITTED"));
    }

    @Test
    void getSagaByTransactionId_returns404WhenNotFound() throws Exception {
        when(sagaOrchestrator.findByTransactionId("TXN-MISSING"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/sagas/TXN-MISSING"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSagaByTransactionId_includesReasonCodeWhenSet() throws Exception {
        SagaSnapshot failed = new SagaSnapshot(
                "SAGA-FAIL", "TXN-FAIL", "E2E-FAIL",
                PaymentSaga.SagaState.FAILED, Rail.RTP,
                "AM04", "Insufficient funds in debtor account",
                Instant.parse("2026-05-19T10:00:00Z"),
                Instant.parse("2026-05-19T10:00:18Z"));
        when(sagaOrchestrator.findByTransactionId("TXN-FAIL")).thenReturn(Optional.of(failed));

        mockMvc.perform(get("/admin/sagas/TXN-FAIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnReasonCode").value("AM04"))
                .andExpect(jsonPath("$.failureDescription")
                        .value("Insufficient funds in debtor account"))
                .andExpect(jsonPath("$.sourceRail").value("RTP"));
    }

    // ── GET /admin/accounts/{accountId}/balance ───────────────────────────────

    @Test
    void getAccountBalance_returnsBalanceView() throws Exception {
        AccountBalanceView view = new AccountBalanceView(
                "ACC-001",
                new BigDecimal("1234.56"),
                new BigDecimal("100.00"),
                Instant.parse("2026-05-19T10:00:00Z"));
        when(shadowLedger.getBalanceView("ACC-001")).thenReturn(view);

        mockMvc.perform(get("/admin/accounts/ACC-001/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ACC-001"))
                .andExpect(jsonPath("$.available").value(1234.56))
                .andExpect(jsonPath("$.reservedPendingCore").value(100.00));
    }

    @Test
    void getAccountBalance_returnsZeroForUnseededAccount() throws Exception {
        AccountBalanceView empty = new AccountBalanceView(
                "ACC-NEW", BigDecimal.ZERO, BigDecimal.ZERO, null);
        when(shadowLedger.getBalanceView("ACC-NEW")).thenReturn(empty);

        mockMvc.perform(get("/admin/accounts/ACC-NEW/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ACC-NEW"))
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.reservedPendingCore").value(0))
                .andExpect(jsonPath("$.lastTransactionAt").doesNotExist());
    }

    @Test
    void getAccountBalance_passesAccountIdThrough() throws Exception {
        when(shadowLedger.getBalanceView("ACC-XYZ"))
                .thenReturn(new AccountBalanceView(
                        "ACC-XYZ", BigDecimal.ZERO, BigDecimal.ZERO, null));

        mockMvc.perform(get("/admin/accounts/ACC-XYZ/balance"))
                .andExpect(status().isOk());

        verify(shadowLedger).getBalanceView("ACC-XYZ");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private SagaSnapshot sampleSnapshot(
            String sagaId, String transactionId, String endToEndId,
            PaymentSaga.SagaState state) {
        return new SagaSnapshot(
                sagaId, transactionId, endToEndId, state, Rail.FEDNOW,
                null, null,
                Instant.parse("2026-05-19T10:00:00Z"),
                Instant.parse("2026-05-19T10:00:05Z"));
    }
}
