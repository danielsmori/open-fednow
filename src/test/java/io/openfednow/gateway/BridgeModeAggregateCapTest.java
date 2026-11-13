package io.openfednow.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.shadowledger.ReconciliationService;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests for the bridge-mode aggregate send cap through
 * {@link MessageRouter#routeOutbound} — issue #68, PR 2.
 *
 * <p>Real Redis, PostgreSQL and RabbitMQ (Testcontainers); the core adapter
 * and the FedNow client are mocks so each test controls core availability,
 * the core's confirmed balance and the rail outcome. Cap is $500.
 *
 * <p>Concurrency discipline follows {@code RedisOutageIntegrationTest}: the
 * invariant under N concurrent sends against one debtor is that the counted
 * aggregate never exceeds the cap, and balance, aggregate, audit log and the
 * rail all agree on which sends went out.
 */
@TestPropertySource(properties = {
        "openfednow.bridge-mode.allow-sends=true",
        "openfednow.bridge-mode.max-aggregate-send-amount=500.00"
})
class BridgeModeAggregateCapTest extends AbstractInfrastructureIntegrationTest {

    private static final String DEBTOR = "ACC-AGG-E2E-DEBTOR";

    @Autowired private MessageRouter messageRouter;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    @MockBean private FedNowClient fedNowClient;
    @MockBean private CoreBankingAdapter coreBankingAdapter;

    @BeforeEach
    void setup() {
        deleteKeys("balance:*");
        deleteKeys("idempotency:*");
        deleteKeys("bridge:aggregate:*");
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM idempotency_keys");

        redis.opsForValue().set("balance:" + DEBTOR, "1000000"); // $10,000.00 — never the constraint
        coreOffline();
        when(fedNowClient.submitCreditTransfer(any())).thenAnswer(inv -> {
            Pacs008Message m = inv.getArgument(0);
            return status(m, Pacs002Message.TransactionStatus.ACSC, null);
        });
    }

    // ── Concurrency ───────────────────────────────────────────────────────────

    @Test
    void concurrentBridgeSendsNeverExceedTheCap() throws Exception {
        int threadCount = 10;
        double capExceededBefore = capExceededCount();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejectedAm04 = new AtomicInteger();
        AtomicInteger errored = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    Pacs002Message r = messageRouter.routeOutbound(
                            message("CONC-" + idx, "100.00")).getBody();
                    if (r != null && r.getTransactionStatus() == Pacs002Message.TransactionStatus.ACSC) {
                        accepted.incrementAndGet();
                    } else if (r != null && "AM04".equals(r.getRejectReasonCode())) {
                        rejectedAm04.incrementAndGet();
                    } else {
                        errored.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    errored.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // exact outcome: the cap is used to the cent, every loser gets a clean
        // AM04, and no send errors (no 500, no orphaned saga)
        assertThat(accepted.get()).isEqualTo(5);
        assertThat(rejectedAm04.get()).isEqualTo(5);
        assertThat(errored.get()).isZero();
        assertThat(countedTotal()).isEqualByComparingTo(new BigDecimal("500.00"));
        Integer openSagas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE state NOT IN ('COMPLETED', 'FAILED')",
                Integer.class);
        assertThat(openSagas).isZero();
        // balance, aggregate, audit log and the rail agree on what went out
        BigDecimal sent = new BigDecimal("100.00").multiply(BigDecimal.valueOf(accepted.get()));
        assertThat(countedTotal()).isEqualByComparingTo(sent);
        assertThat(shadowLedger.getAvailableBalance(DEBTOR))
                .isEqualByComparingTo(new BigDecimal("10000.00").subtract(sent));
        assertThat(debitRows()).isEqualTo(accepted.get());
        verify(fedNowClient, times(accepted.get())).submitCreditTransfer(any());
        // every AM04 was a cap rejection
        assertThat(capExceededCount() - capExceededBefore).isEqualTo(rejectedAm04.get());
    }

    @Test
    void sequentialBridgeSendsStopExactlyAtTheCap() {
        for (int i = 0; i < 5; i++) {
            assertThat(status(messageRouter.routeOutbound(message("SEQ-" + i, "100.00"))))
                    .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        }
        Pacs002Message sixth = messageRouter.routeOutbound(message("SEQ-5", "0.01")).getBody();

        assertThat(sixth.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(sixth.getRejectReasonCode()).isEqualTo("AM04");
        assertThat(countedTotal()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ── Rejection shape ───────────────────────────────────────────────────────

    @Test
    void capRejectionIsAm04WithNoSagaNoDebitAndIsIdempotent() {
        double capExceededBefore = capExceededCount();
        messageRouter.routeOutbound(message("SHAPE-1", "400.00"));

        Pacs002Message rejected = messageRouter.routeOutbound(message("SHAPE-2", "200.00")).getBody();
        Pacs002Message retried = messageRouter.routeOutbound(message("SHAPE-2", "200.00")).getBody();

        assertThat(rejected.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(rejected.getRejectReasonCode()).isEqualTo("AM04");
        assertThat(retried.getRejectReasonCode()).isEqualTo("AM04");
        assertThat(sagaCount("TXN-SHAPE-2")).isZero();
        assertThat(shadowLedger.getAvailableBalance(DEBTOR)).isEqualByComparingTo("9600.00");
        verify(fedNowClient, times(1)).submitCreditTransfer(any());
        // the retry was answered from the idempotency cache, not re-evaluated
        assertThat(capExceededCount() - capExceededBefore).isEqualTo(1.0);
    }

    // ── Release semantics ─────────────────────────────────────────────────────

    @Test
    void fedNowRejectionReleasesTheCap() {
        // doAnswer, not when(...): re-stubbing via when() would invoke the
        // setup() answer with a null argument
        doAnswer(inv -> {
            Pacs008Message m = inv.getArgument(0);
            return "TXN-REL-1".equals(m.getTransactionId())
                    ? status(m, Pacs002Message.TransactionStatus.RJCT, "AC04")
                    : status(m, Pacs002Message.TransactionStatus.ACSC, null);
        }).when(fedNowClient).submitCreditTransfer(any());

        messageRouter.routeOutbound(message("REL-1", "400.00"));
        assertThat(sagaState("TXN-REL-1")).isEqualTo("FAILED");
        assertThat(countedTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        // releasing every send empties the window but must not close it: only
        // reconciliation confirms the balance, and until then online sends are
        // still counted against it
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isTrue();

        assertThat(status(messageRouter.routeOutbound(message("REL-2", "400.00"))))
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    @Test
    void unknownOutcomeStaysCounted() {
        doThrow(new RuntimeException("transport lost")).when(fedNowClient).submitCreditTransfer(any());

        ResponseEntity<Pacs002Message> unknown = messageRouter.routeOutbound(message("UNK-1", "400.00"));
        assertThat(unknown.getStatusCode().value()).isEqualTo(503);
        assertThat(sagaState("TXN-UNK-1")).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(countedTotal()).isEqualByComparingTo(new BigDecimal("400.00"));

        Pacs002Message next = messageRouter.routeOutbound(message("UNK-2", "200.00")).getBody();
        assertThat(next.getRejectReasonCode()).isEqualTo("AM04");

        // reconciliation with the core back does not release an unresolved send
        coreOnline(new BigDecimal("9600.00"));
        reconciliationService.reconcile();
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isTrue();
        assertThat(countedTotal()).isEqualByComparingTo(new BigDecimal("400.00"));
    }

    // ── Window lifecycle ──────────────────────────────────────────────────────

    @Test
    void capStaysInForceAfterCoreReturnsUntilReconciliationConfirms() {
        messageRouter.routeOutbound(message("LIFE-1", "400.00"));

        coreOnline(new BigDecimal("9600.00"));
        // core is back, but the balance is not confirmed yet: still capped
        assertThat(messageRouter.routeOutbound(message("LIFE-2", "200.00")).getBody().getRejectReasonCode())
                .isEqualTo("AM04");

        reconciliationService.reconcile();
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isFalse();

        // confirmed and online: sends flow and are not counted
        assertThat(status(messageRouter.routeOutbound(message("LIFE-3", "200.00"))))
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isFalse();
    }

    @Test
    void reconciliationDuringAnOutageLeavesTheWindowOpen() {
        messageRouter.routeOutbound(message("OUT-1", "400.00"));
        when(coreBankingAdapter.getAvailableBalance(anyString())).thenReturn(new BigDecimal("9600.00"));

        reconciliationService.reconcile(); // core still offline

        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isTrue();
        // the account was deferred, not confirmed against the offline core
        Integer unconfirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = ? AND core_confirmed = FALSE",
                Integer.class, DEBTOR);
        assertThat(unconfirmed).isEqualTo(1);
        assertThat(messageRouter.routeOutbound(message("OUT-2", "200.00")).getBody().getRejectReasonCode())
                .isEqualTo("AM04");
    }

    @Test
    void reconciliationSweepReleasesSendsWhoseRowsAreAlreadyConfirmed() {
        messageRouter.routeOutbound(message("SWEEP-1", "100.00"));
        // simulate a previous cycle that committed the confirmation but failed
        // to release the sends: the account has no unconfirmed rows left, so
        // only the open-window sweep can reach it
        jdbc.update("UPDATE shadow_ledger_transaction_log SET core_confirmed = TRUE");
        // the core reports a different figure, so a balance reset would show
        coreOnline(new BigDecimal("5000.00"));

        reconciliationService.reconcile();

        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isFalse();
        // the sweep releases sends only; it does not reset the balance
        assertThat(redis.opsForValue().get("balance:" + DEBTOR)).isEqualTo("990000");
    }

    @Test
    void onlineSendsWithoutAnOpenWindowAreNeverCounted() {
        coreOnline(new BigDecimal("10000.00"));

        for (int i = 0; i < 3; i++) {
            assertThat(status(messageRouter.routeOutbound(message("ONLINE-" + i, "400.00"))))
                    .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        }
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void coreOffline() {
        when(coreBankingAdapter.isCoreSystemAvailable()).thenReturn(false);
    }

    private void coreOnline(BigDecimal confirmedBalance) {
        when(coreBankingAdapter.isCoreSystemAvailable()).thenReturn(true);
        when(coreBankingAdapter.getAvailableBalance(anyString())).thenReturn(confirmedBalance);
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private BigDecimal countedTotal() {
        var view = shadowLedger.getAggregateView(DEBTOR);
        return view == null ? BigDecimal.ZERO : view.totalCounted();
    }

    private double capExceededCount() {
        var counter = meterRegistry.find(MessageRouter.AGGREGATE_CAP_EXCEEDED_METRIC).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private int debitRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = ? AND transaction_type = 'DEBIT'",
                Integer.class, DEBTOR);
        return n == null ? 0 : n;
    }

    private int sagaCount(String transactionId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = ?", Integer.class, transactionId);
        return n == null ? 0 : n;
    }

    private String sagaState(String transactionId) {
        return jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE transaction_id = ?", String.class, transactionId);
    }

    private static Pacs002Message.TransactionStatus status(ResponseEntity<Pacs002Message> response) {
        return response.getBody() == null ? null : response.getBody().getTransactionStatus();
    }

    private static Pacs002Message status(Pacs008Message m, Pacs002Message.TransactionStatus status,
                                         String reasonCode) {
        return Pacs002Message.builder()
                .originalEndToEndId(m.getEndToEndId())
                .originalTransactionId(m.getTransactionId())
                .transactionStatus(status)
                .rejectReasonCode(reasonCode)
                .rejectReasonDescription(reasonCode == null ? null : "Synthetic rejection: " + reasonCode)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    private static Pacs008Message message(String id, String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + id)
                .endToEndId("E2E-" + id)
                .transactionId("TXN-" + id)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(DEBTOR)
                .creditorAccountNumber("ACC-AGG-E2E-CREDITOR")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .numberOfTransactions(1)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }
}
