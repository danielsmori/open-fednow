package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.processing.saga.SagaSnapshot;
import io.openfednow.shadowledger.AccountBalanceView;
import io.openfednow.shadowledger.ReconciliationRunSummary;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        sagaOrchestrator = mock(SagaOrchestrator.class);
        shadowLedger = mock(ShadowLedger.class);
        reconciliationService = mock(ReconciliationService.class);

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

    // ── GET /admin/reconciliation-runs ────────────────────────────────────────

    @Test
    void listReconciliationRuns_returnsEmptyWhenNoneRecorded() throws Exception {
        when(reconciliationService.listRecentRuns(anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/reconciliation-runs"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void listReconciliationRuns_returnsRowsInOrder() throws Exception {
        ReconciliationRunSummary newest = sampleRun(42L, true, "SCHEDULED");
        ReconciliationRunSummary older = sampleRun(41L, true, "MANUAL");
        when(reconciliationService.listRecentRuns(anyInt(), anyInt()))
                .thenReturn(List.of(newest, older));

        mockMvc.perform(get("/admin/reconciliation-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].triggeredBy").value("SCHEDULED"))
                .andExpect(jsonPath("$[1].id").value(41))
                .andExpect(jsonPath("$[1].triggeredBy").value("MANUAL"));
    }

    @Test
    void listReconciliationRuns_appliesDefaultLimitAndOffset() throws Exception {
        when(reconciliationService.listRecentRuns(50, 0)).thenReturn(List.of());

        mockMvc.perform(get("/admin/reconciliation-runs")).andExpect(status().isOk());

        verify(reconciliationService).listRecentRuns(50, 0);
    }

    @Test
    void listReconciliationRuns_clampsLimitToMaximum() throws Exception {
        when(reconciliationService.listRecentRuns(eq(AdminController.RECON_RUNS_MAX_LIMIT), anyInt()))
                .thenReturn(List.of());

        // Request a limit larger than the cap; controller must clamp it down
        mockMvc.perform(get("/admin/reconciliation-runs?limit=1000"))
                .andExpect(status().isOk());

        verify(reconciliationService).listRecentRuns(AdminController.RECON_RUNS_MAX_LIMIT, 0);
    }

    @Test
    void listReconciliationRuns_clampsNegativeLimitAndOffsetToValidValues() throws Exception {
        when(reconciliationService.listRecentRuns(anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/reconciliation-runs?limit=-5&offset=-3"))
                .andExpect(status().isOk());

        // Negative limit clamps to 1 (Math.max(1, min(limit, MAX))); negative offset clamps to 0
        verify(reconciliationService).listRecentRuns(1, 0);
    }

    @Test
    void listReconciliationRuns_passesExplicitPaginationThrough() throws Exception {
        when(reconciliationService.listRecentRuns(25, 50)).thenReturn(List.of());

        mockMvc.perform(get("/admin/reconciliation-runs?limit=25&offset=50"))
                .andExpect(status().isOk());

        verify(reconciliationService).listRecentRuns(25, 50);
    }

    // ── GET /admin/reconciliation-runs/{runId} ────────────────────────────────

    @Test
    void getReconciliationRun_returnsSummaryWhenFound() throws Exception {
        when(reconciliationService.findRunById(7L))
                .thenReturn(Optional.of(sampleRun(7L, true, "SCHEDULED")));

        mockMvc.perform(get("/admin/reconciliation-runs/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.successful").value(true))
                .andExpect(jsonPath("$.triggeredBy").value("SCHEDULED"));
    }

    @Test
    void getReconciliationRun_returns404WhenNotFound() throws Exception {
        when(reconciliationService.findRunById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/reconciliation-runs/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReconciliationRun_exposesInProgressRunsWithNullCompletedAt() throws Exception {
        ReconciliationRunSummary inProgress = new ReconciliationRunSummary(
                12L, Instant.parse("2026-05-19T10:00:00Z"), null,
                0, 0, null, null, "MANUAL");
        when(reconciliationService.findRunById(12L)).thenReturn(Optional.of(inProgress));

        mockMvc.perform(get("/admin/reconciliation-runs/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.successful").doesNotExist());
    }

    // ── POST /admin/reconciliation-runs ───────────────────────────────────────

    @Test
    void triggerReconciliationRun_invokesReconcileAndReturnsReport() throws Exception {
        ReconciliationService.ReconciliationReport report =
                new ReconciliationService.ReconciliationReport(5, 0, true, "Clean reconciliation");
        when(reconciliationService.reconcile()).thenReturn(report);

        mockMvc.perform(post("/admin/reconciliation-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsReplayed").value(5))
                .andExpect(jsonPath("$.discrepanciesDetected").value(0))
                .andExpect(jsonPath("$.reconciliationSuccessful").value(true));

        verify(reconciliationService).reconcile();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ReconciliationRunSummary sampleRun(long id, boolean successful, String triggeredBy) {
        return new ReconciliationRunSummary(
                id,
                Instant.parse("2026-05-19T10:00:00Z"),
                Instant.parse("2026-05-19T10:00:05Z"),
                3, 0, successful,
                successful ? "Clean reconciliation" : "Discrepancies found",
                triggeredBy);
    }

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
