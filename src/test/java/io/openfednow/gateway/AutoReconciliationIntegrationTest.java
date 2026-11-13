package io.openfednow.gateway;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end test for automatic reconciliation on core recovery — issue #68,
 * PR 3.
 *
 * <p>The full production path, no manual {@code reconcile()} call anywhere:
 * a bridge-mode send opens an aggregate window and caps the account; the core
 * comes back; the availability poll (1s here) observes the transition and
 * starts a reconciliation cycle on its own; the cycle confirms the rows and
 * lifts the cap.
 */
@TestPropertySource(properties = {
        "openfednow.bridge-mode.allow-sends=true",
        "openfednow.bridge-mode.max-aggregate-send-amount=500.00",
        "openfednow.reconciliation.auto-on-core-recovery=true",
        "openfednow.shadow-ledger.reconciliation-poll-interval-seconds=1"
})
class AutoReconciliationIntegrationTest extends AbstractInfrastructureIntegrationTest {

    private static final String DEBTOR = "ACC-AUTO-RECON-DEBTOR";

    @Autowired private MessageRouter messageRouter;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private FedNowClient fedNowClient;
    @MockBean private CoreBankingAdapter coreBankingAdapter;

    /** Core availability switch read by the stub; safe to flip mid-test. */
    private final java.util.concurrent.atomic.AtomicBoolean coreUp =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @BeforeEach
    void setup() {
        deleteKeys("balance:*");
        deleteKeys("idempotency:*");
        deleteKeys("bridge:aggregate:*");
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("UPDATE reconciliation_lease SET holder = NULL, leased_until = NULL WHERE id = 1");

        redis.opsForValue().set("balance:" + DEBTOR, "1000000"); // $10,000.00
        // Stub ONCE through a flag: the 1s poll thread hits this mock while
        // the test runs, and re-stubbing a mock another thread is invoking
        // races inside Mockito. Flipping an AtomicBoolean is safe.
        coreUp.set(false);
        when(coreBankingAdapter.isCoreSystemAvailable()).thenAnswer(inv -> coreUp.get());
        when(coreBankingAdapter.getAvailableBalance(anyString()))
                .thenAnswer(inv -> new BigDecimal("9600.00"));
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
    void coreRecoveryLiftsTheCapWithoutAnyManualTrigger() {
        // Bridge mode: a $400 send opens the window; $200 more would exceed the cap
        assertThat(messageRouter.routeOutbound(message("AUTO-1", "400.00")).getBody()
                .getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        assertThat(messageRouter.routeOutbound(message("AUTO-2", "200.00")).getBody()
                .getRejectReasonCode()).isEqualTo("AM04");
        assertThat(shadowLedger.hasOpenAggregate(DEBTOR)).isTrue();

        // The core comes back; nobody calls reconcile()
        coreUp.set(true);

        // The 1s availability poll must observe the recovery and reconcile
        awaitTrue("aggregate window closed by automatic reconciliation",
                () -> !shadowLedger.hasOpenAggregate(DEBTOR));

        // the cycle confirmed the ledger rows
        Integer unconfirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log WHERE core_confirmed = FALSE",
                Integer.class);
        assertThat(unconfirmed).isZero();
        // the run is attributed to the automatic trigger in the audit table
        Integer autoRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run WHERE triggered_by = 'AUTO_RECOVERY'",
                Integer.class);
        assertThat(autoRuns).isGreaterThanOrEqualTo(1);
        // the lease is released once the cycle finishes (the window closes
        // mid-cycle, so poll until the cycle is actually over)
        awaitTrue("reconciliation lease released", () -> jdbc.queryForObject(
                "SELECT holder FROM reconciliation_lease WHERE id = 1", String.class) == null);

        // and the account can send again
        assertThat(messageRouter.routeOutbound(message("AUTO-3", "200.00")).getBody()
                .getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Polls up to 30s; the availability poll runs every second in this test. */
    private static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + what, e);
            }
        }
        throw new AssertionError("Timed out after 30s waiting for: " + what);
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private static Pacs008Message message(String id, String amount) {
        return Pacs008Message.builder()
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
                .build();
    }
}
