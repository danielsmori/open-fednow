package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.gateway.FedNowClient;
import io.openfednow.gateway.MessageRouter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Automatic reconciliation on core recovery, end to end — issue #68, PR 3.
 *
 * <p>Real Redis, PostgreSQL and RabbitMQ; the core adapter and FedNow client
 * are mocks. The availability poll is invoked directly (the scheduled poll
 * also runs in the background; both are safe to interleave). The point under
 * test: after an outage, a capped account is released <em>without an
 * operator</em>, and the pending marker only clears once that has happened.
 */
@TestPropertySource(properties = {
        "openfednow.reconciliation.auto-on-core-recovery=true",
        "openfednow.bridge-mode.allow-sends=true",
        "openfednow.bridge-mode.max-aggregate-send-amount=500.00",
        // only the startup poll runs on its own; the tests drive every other poll
        "openfednow.shadow-ledger.reconciliation-poll-interval-seconds=3600",
        // retries of an unsettled cycle wait 1s here instead of 30s+
        "openfednow.reconciliation.auto-retry-backoff-initial-seconds=1",
        "openfednow.reconciliation.auto-retry-backoff-max-seconds=1"
})
class AutomaticReconciliationIntegrationTest extends AbstractInfrastructureIntegrationTest {

    private static final String DEBTOR = "ACC-AUTO-RECON-DEBTOR";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired private AvailabilityBridge availabilityBridge;
    @Autowired private MessageRouter messageRouter;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private ReconciliationPendingMarker pendingMarker;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @Autowired private BridgeAggregateWindowMonitor windowMonitor;

    @MockBean private CoreBankingAdapter coreBankingAdapter;
    @MockBean private FedNowClient fedNowClient;

    @BeforeEach
    void setup() {
        deleteKeys("balance:*");
        deleteKeys("idempotency:*");
        deleteKeys("bridge:aggregate:*");
        redis.delete(ReconciliationPendingMarker.PENDING_KEY);
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM reconciliation_run");

        redis.opsForValue().set("balance:" + DEBTOR, "1000000"); // $10,000.00
        when(fedNowClient.submitCreditTransfer(any())).thenAnswer(inv -> {
            Pacs008Message m = inv.getArgument(0);
            return Pacs002Message.builder()
                    .originalEndToEndId(m.getEndToEndId())
                    .originalTransactionId(m.getTransactionId())
                    .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                    .creationDateTime(OffsetDateTime.now())
                    .build();
        });
    }

    @Test
    void coreRecoveryReleasesTheCapWithoutAnOperator() {
        coreOffline();
        availabilityBridge.pollCoreAvailability();
        // the poll's mark is written off the scheduler thread
        awaitTrue(() -> pendingMarker.currentToken().isPresent());

        send("AUTO-1", "400.00");
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isTrue();
        // capped while the window is open
        assertThat(send("AUTO-2", "200.00").getRejectReasonCode()).isEqualTo("AM04");

        coreOnline(new BigDecimal("9600.00"));

        awaitTrue(() -> {
            availabilityBridge.pollCoreAvailability();
            return !shadowLedger.hasOpenAggregate(DEBTOR) && pendingMarker.currentToken().isEmpty();
        });
        assertThat(unconfirmedRows()).isZero();
        assertThat(reconciliationRuns()).isEqualTo(1);
        // and the account can send again
        assertThat(send("AUTO-3", "200.00").getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    @Test
    void outageShorterThanOnePollIsStillReconciled() {
        // The poll never sees the core offline: it is offline only while the
        // send is processed. The counted send itself records the owed cycle.
        coreOffline();
        send("SHORT-1", "100.00");
        assertThat(pendingMarker.currentToken()).isPresent();

        coreOnline(new BigDecimal("9900.00"));

        awaitTrue(() -> {
            availabilityBridge.pollCoreAvailability();
            return !shadowLedger.hasOpenAggregate(DEBTOR) && pendingMarker.currentToken().isEmpty();
        });
    }

    @Test
    void noBridgeActivityMeansNoAutomaticCycle() throws Exception {
        coreOnline(new BigDecimal("10000.00"));
        // drop a mark the startup poll may have left while the mock still said offline
        redis.delete(ReconciliationPendingMarker.PENDING_KEY);

        availabilityBridge.pollCoreAvailability();
        availabilityBridge.pollCoreAvailability();
        Thread.sleep(300); // give a (wrongly) started background cycle time to show up

        assertThat(reconciliationRuns()).isZero();
    }

    @Test
    void cycleThatLeavesAnAccountUnreconciledIsRetriedOnTheNextPoll() {
        coreOffline();
        availabilityBridge.pollCoreAvailability();
        send("RETRY-1", "100.00");

        // first cycle: the core fails this account's balance lookup
        when(coreBankingAdapter.isCoreSystemAvailable()).thenReturn(true);
        when(coreBankingAdapter.getAvailableBalance(anyString()))
                .thenThrow(new RuntimeException("core read timeout"))
                .thenReturn(new BigDecimal("9900.00"));
        awaitTrue(() -> {
            availabilityBridge.pollCoreAvailability();
            return reconciliationRuns() >= 1 && runFinished();
        });
        assertThat(pendingMarker.currentToken()).as("failed account keeps the marker").isPresent();
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isTrue();

        // later polls: after the backoff, the retry succeeds and settles everything
        awaitTrue(() -> {
            availabilityBridge.pollCoreAvailability();
            return !shadowLedger.hasOpenAggregate(DEBTOR) && pendingMarker.currentToken().isEmpty();
        });
        assertThat(reconciliationRuns()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void sendWithAnUnresolvedRailOutcomeKeepsReconciliationOwedUntilItResolves() {
        coreOffline();
        availabilityBridge.pollCoreAvailability();
        doThrow(new RuntimeException("transport lost")).when(fedNowClient).submitCreditTransfer(any());
        send("UNRES-1", "400.00"); // 503, saga OUTCOME_UNKNOWN, send stays counted

        coreOnline(new BigDecimal("9600.00"));
        double unsettledBefore = unsettledCycles();
        // wait for the trigger's own verdict on the cycle, not just the run row:
        // the marker decision is taken after the run record is closed
        awaitTrue(() -> {
            availabilityBridge.pollCoreAvailability();
            return unsettledCycles() > unsettledBefore;
        });
        // the cycle confirmed the debit but could not release an unresolved send:
        // the account is still capped, so the owed reconciliation is not settled
        assertThat(unconfirmedRows()).isZero();
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isTrue();
        assertThat(pendingMarker.currentToken()).isPresent();

        // the outcome is resolved (e.g. investigated and found settled) ...
        jdbc.update("UPDATE saga_state SET state = 'COMPLETED' WHERE transaction_id = 'TXN-UNRES-1'");
        // ... and a later automatic cycle releases the account, still with no operator
        awaitTrue(() -> {
            availabilityBridge.pollCoreAvailability();
            return !shadowLedger.hasOpenAggregate(DEBTOR) && pendingMarker.currentToken().isEmpty();
        });
    }

    @Test
    void windowGaugesTrackTheCappedAccountUntilReconciliationReleasesIt() {
        coreOffline();
        send("GAUGE-1", "400.00"); // 80% of the $500 cap

        windowMonitor.scan();
        assertThat(gaugeValue(BridgeAggregateWindowMonitor.WINDOWS_OPEN_METRIC)).isEqualTo(1);
        assertThat(gaugeValue(BridgeAggregateWindowMonitor.WINDOWS_NEAR_CAP_METRIC)).isEqualTo(1);
        assertThat(gaugeValue(BridgeAggregateWindowMonitor.WINDOWS_OVERDUE_METRIC)).isZero();

        coreOnline(new BigDecimal("9600.00"));
        awaitTrue(() -> {
            availabilityBridge.pollCoreAvailability();
            return !shadowLedger.hasOpenAggregate(DEBTOR);
        });
        windowMonitor.scan();
        assertThat(gaugeValue(BridgeAggregateWindowMonitor.WINDOWS_OPEN_METRIC)).isZero();
        assertThat(gaugeValue(BridgeAggregateWindowMonitor.WINDOWS_NEAR_CAP_METRIC)).isZero();
    }

    @Test
    void markerIsClearedOnlyByTheTokenThatWasRead() {
        pendingMarker.mark("first");
        String first = pendingMarker.currentToken().orElseThrow();
        pendingMarker.mark("activity during the cycle");
        String second = pendingMarker.currentToken().orElseThrow();

        assertThat(second).isNotEqualTo(first);
        assertThat(pendingMarker.clearIfUnchanged(first)).isFalse();
        assertThat(pendingMarker.currentToken()).contains(second);
        assertThat(pendingMarker.clearIfUnchanged(second)).isTrue();
        assertThat(pendingMarker.currentToken()).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Pacs002Message send(String id, String amount) {
        return messageRouter.routeOutbound(Pacs008Message.builder()
                .messageId("MSG-" + id)
                .endToEndId("E2E-" + id)
                .transactionId("TXN-" + id)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(DEBTOR)
                .creditorAccountNumber("ACC-AUTO-RECON-CREDITOR")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .numberOfTransactions(1)
                .creationDateTime(OffsetDateTime.now())
                .build()).getBody();
    }

    private void coreOffline() {
        when(coreBankingAdapter.isCoreSystemAvailable()).thenReturn(false);
    }

    private void coreOnline(BigDecimal confirmedBalance) {
        when(coreBankingAdapter.isCoreSystemAvailable()).thenReturn(true);
        when(coreBankingAdapter.getAvailableBalance(anyString())).thenReturn(confirmedBalance);
    }

    private double gaugeValue(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private double unsettledCycles() {
        var counter = meterRegistry.find(AutomaticReconciliationTrigger.CYCLES_METRIC)
                .tag("result", "unsettled").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private int unconfirmedRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log WHERE core_confirmed = FALSE",
                Integer.class);
        return n == null ? 0 : n;
    }

    private int reconciliationRuns() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_run", Integer.class);
        return n == null ? 0 : n;
    }

    private boolean runFinished() {
        Integer open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run WHERE completed_at IS NULL", Integer.class);
        return open != null && open == 0;
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition not met within " + WAIT);
    }
}
