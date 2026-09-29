package io.openfednow.reliability;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReliablePaymentIntegrationTest extends AbstractInfrastructureIntegrationTest {
    @Autowired ReliablePaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockBean ReliabilityRailPort rail;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM reliability_investigation_audit");
        jdbc.update("DELETE FROM reliability_status_event");
        jdbc.update("DELETE FROM reliability_effect");
        jdbc.update("DELETE FROM reliability_core_ack");
        jdbc.update("DELETE FROM reliability_payment");
        jdbc.update("DELETE FROM reliability_account");
        payments.provisionSyntheticAccount("SYN-DEBTOR", 100_000, true);
    }

    @Test
    void acceptancePostsOnceAndDuplicateRetrievesSameOperation() {
        Pacs008Message request = payment("E2E-ACCEPT", "TX-ACCEPT", "100.00");
        when(rail.submit(any(), anyString())).thenReturn(status(request, "SETTLED", "R-1"));

        PaymentView first = payments.submit(request);
        PaymentView again = payments.submit(request);

        assertThat(first.state()).isEqualTo("SETTLED");
        assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(90_000);
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isZero();
        assertThat(again.operationId()).isEqualTo(first.operationId());
        assertThat(payments.effects(first.operationId()).stream().map(ReliablePaymentService.EffectView::effectType))
                .containsExactlyInAnyOrder("HOLD", "POST");
        verify(rail, times(1)).submit(any(), anyString());
    }

    @Test
    void lostAcceptanceStaysUnknownUntilInquiryAndNeverSubmitsAgain() {
        Pacs008Message request = payment("E2E-LOST", "TX-LOST", "125.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        PaymentView pending = payments.submit(request);
        assertThat(pending.state()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(pending.attemptId()).isNotBlank();
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isEqualTo(12_500);
        assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(100_000);

        payments.submit(request);
        verify(rail, times(1)).submit(any(), anyString());

        jdbc.update("UPDATE reliability_payment SET next_inquiry_at = NOW() - INTERVAL '1 second' WHERE operation_id = ?",
                pending.operationId());
        var leases = payments.claimDueInquiries(5);
        assertThat(leases).hasSize(1);
        PaymentView settled = payments.finishInquiry(leases.get(0), status(request, "SETTLED", "R-LATE"));
        assertThat(settled.state()).isEqualTo("SETTLED");
        assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(87_500);
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isZero();
        payments.observe(pending.operationId(), status(request, "SETTLED", "R-LATE"));
        assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(87_500);
        assertThat(payments.effects(pending.operationId())).hasSize(2);
    }

    @Test
    void possibleSendIntentSurvivesWorkerCrashWithoutReplay() {
        Pacs008Message request = payment("E2E-CRASH-INTENT", "TX-CRASH-INTENT", "65.00");
        when(rail.submit(any(), anyString()))
                .thenThrow(new AssertionError("simulated process death"))
                .thenReturn(status(request, "SETTLED", "R-ILLEGAL-REPLAY"));
        assertThatThrownBy(() -> payments.submit(request)).isInstanceOf(AssertionError.class);
        payments.dispatchRecoverable(20);
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isEqualTo(6_500);
        PaymentView pending = payments.getByBusinessKey("021000021", request.getEndToEndId());
        assertThat(pending.state()).isEqualTo("SUBMITTING");
        verify(rail, times(1)).submit(any(), anyString());
    }

    @Test
    void lostRejectionReleasesExactlyOnceAfterInquiry() {
        Pacs008Message request = payment("E2E-REJECT", "TX-REJECT", "75.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        PaymentView pending = payments.submit(request);
        payments.observe(pending.operationId(), status(request, "REJECTED", "R-REJECT"));
        payments.observe(pending.operationId(), status(request, "REJECTED", "R-REJECT-2"));
        assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(100_000);
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isZero();
        assertThat(payments.effects(pending.operationId()).stream().map(ReliablePaymentService.EffectView::effectType))
                .containsExactlyInAnyOrder("HOLD", "RELEASE");
    }

    @Test
    void changedPayloadConflictsWithoutSecondHold() {
        Pacs008Message original = payment("E2E-CONFLICT", "TX-CONFLICT", "50.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        payments.submit(original);
        Pacs008Message changed = payment("E2E-CONFLICT", "TX-CONFLICT", "60.00");
        assertThatThrownBy(() -> payments.submit(changed)).isInstanceOf(ResponseStatusException.class);
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isEqualTo(5_000);
        verify(rail, times(1)).submit(any(), anyString());
    }

    @Test
    void noExclusiveCoreReservationRefusesSend() {
        payments.provisionSyntheticAccount("SHARED-CHANNEL", 100_000, false);
        Pacs008Message request = payment("E2E-SHARED", "TX-SHARED", "100.00");
        request.setDebtorAccountNumber("SHARED-CHANNEL");
        payments.syntheticExternalDebit("SHARED-CHANNEL", 95_000);
        assertThatThrownBy(() -> payments.submit(request)).isInstanceOf(ResponseStatusException.class);
        assertThat(payments.account("SHARED-CHANNEL").ledgerMinor()).isEqualTo(5_000);
        assertThat(payments.account("SHARED-CHANNEL").heldMinor()).isZero();
        verify(rail, times(0)).submit(any(), anyString());
    }

    @Test
    void asynchronousCoreAckPrecedesRailSendAndIsRecoveredByLookup() {
        payments.provisionSyntheticAccount("ASYNC-DEBTOR", 100_000, true, "ASYNC");
        Pacs008Message request = payment("E2E-ASYNC", "TX-ASYNC", "120.00");
        request.setDebtorAccountNumber("ASYNC-DEBTOR");
        when(rail.submit(any(), anyString())).thenReturn(status(request, "SETTLED", "R-ASYNC"));

        PaymentView pending = payments.submit(request);
        assertThat(pending.state()).isEqualTo("CORE_PENDING");
        assertThat(payments.account("ASYNC-DEBTOR").heldMinor()).isEqualTo(12_000);
        verify(rail, times(0)).submit(any(), anyString());

        PaymentView settled = payments.acknowledgeSyntheticCore(pending.operationId());
        assertThat(settled.state()).isEqualTo("SETTLED");
        assertThat(payments.account("ASYNC-DEBTOR").ledgerMinor()).isEqualTo(88_000);
        assertThat(payments.account("ASYNC-DEBTOR").heldMinor()).isZero();
        verify(rail, times(1)).submit(any(), anyString());
    }

    @Test
    void redisLossCannotEraseSqlOwnershipOrCauseSecondSend() {
        Pacs008Message request = payment("E2E-CACHE-LOSS", "TX-CACHE-LOSS", "40.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        PaymentView pending = payments.submit(request);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        PaymentView duplicate = payments.submit(request);
        assertThat(duplicate.operationId()).isEqualTo(pending.operationId());
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isEqualTo(4_000);
        verify(rail, times(1)).submit(any(), anyString());
    }

    @Test
    void conflictingLateEventDoesNotUndoSettlementAndPostingIsSeparate() {
        Pacs008Message request = payment("E2E-CONFLICT-EVENT", "TX-CONFLICT-EVENT", "80.00");
        when(rail.submit(any(), anyString())).thenReturn(status(request, "SETTLED", "R-FINAL"));
        PaymentView settled = payments.submit(request);
        PaymentView posted = payments.observeSyntheticPosting(
                settled.operationId(), "POSTED", "RECEIVER-1");
        assertThat(posted.serviceStatus()).isEqualTo("SETTLED");
        assertThat(posted.postingStatus()).isEqualTo("POSTED");
        PaymentView conflict = payments.observe(settled.operationId(),
                status(request, "REJECTED", "R-CONFLICT"));
        assertThat(conflict.state()).isEqualTo("SETTLED");
        assertThat(conflict.investigationReason()).contains("Conflicting");
        assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(92_000);
        assertThat(payments.effects(settled.operationId()).stream()
                .map(ReliablePaymentService.EffectView::effectType))
                .containsExactlyInAnyOrder("HOLD", "POST");
    }

    @Test
    void inquiryLeaseFencesStaleWorkerAndIsNotEligibleEarly() {
        Pacs008Message request = payment("E2E-LEASE", "TX-LEASE", "20.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        PaymentView pending = payments.submit(request);
        assertThat(payments.claimDueInquiries(5)).isEmpty();
        payments.makeSyntheticInquiryDue(pending.operationId());
        var first = payments.claimDueInquiries(5);
        assertThat(first).hasSize(1);
        assertThat(payments.claimDueInquiries(5)).isEmpty();
        jdbc.update("""
                UPDATE reliability_payment SET inquiry_lease_token = 'new-worker',
                    inquiry_lease_until = NOW() + INTERVAL '15 seconds'
                WHERE operation_id = ?
                """, pending.operationId());
        PaymentView stale = payments.finishInquiry(first.get(0), status(request, "REJECTED", "R-STALE"));
        assertThat(stale.state()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isEqualTo(2_000);
    }

    @Test
    void failedReleaseRollsBackFinancialEffectAndCanRetry() {
        Pacs008Message request = payment("E2E-RELEASE-FAIL", "TX-RELEASE-FAIL", "30.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        PaymentView pending = payments.submit(request);
        jdbc.update("UPDATE reliability_account SET held_minor = 0 WHERE account_id = 'SYN-DEBTOR'");
        assertThatThrownBy(() -> payments.observe(pending.operationId(),
                status(request, "REJECTED", "R-RELEASE")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.get(pending.operationId()).state()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(payments.effects(pending.operationId()).stream()
                .map(ReliablePaymentService.EffectView::effectType)).containsExactly("HOLD");
        jdbc.update("UPDATE reliability_account SET held_minor = 3000 WHERE account_id = 'SYN-DEBTOR'");
        PaymentView recovered = payments.observe(pending.operationId(),
                status(request, "REJECTED", "R-RELEASE"));
        assertThat(recovered.state()).isEqualTo("REJECTED");
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isZero();
    }

    @Test
    void untraceableInquiryEscalatesWithoutReleasingFundsAndAuditRecordsReview() {
        Pacs008Message request = payment("E2E-UNTRACEABLE", "TX-UNTRACEABLE", "35.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        PaymentView pending = payments.submit(request);
        for (int i = 0; i < 3; i++) {
            payments.makeSyntheticInquiryDue(pending.operationId());
            var lease = payments.claimDueInquiries(1).get(0);
            payments.finishInquiry(lease, null);
        }
        PaymentView investigated = payments.get(pending.operationId());
        assertThat(investigated.state()).isEqualTo("INVESTIGATION");
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isEqualTo(3_500);
        payments.review(pending.operationId(), "synthetic-operator", "checked simulator",
                "SIM-EVIDENCE-001");
        Integer audits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reliability_investigation_audit WHERE operation_id = ?
                """, Integer.class, pending.operationId());
        assertThat(audits).isEqualTo(1);
        assertThat(payments.get(pending.operationId()).state()).isEqualTo("INVESTIGATION");
    }

    @Test
    void reviewResolutionAndAuditCommitTogetherOrRollBackTogether() {
        Pacs008Message request = payment("E2E-AUDIT-ATOMIC", "TX-AUDIT-ATOMIC", "35.00");
        when(rail.submit(any(), anyString())).thenReturn(null);
        PaymentView pending = payments.submit(request);
        for (int i = 0; i < 3; i++) {
            payments.makeSyntheticInquiryDue(pending.operationId());
            payments.finishInquiry(payments.claimDueInquiries(1).get(0), null);
        }
        when(rail.inquire(any())).thenReturn(status(request, "SETTLED", "R-REVIEW"));

        jdbc.update("UPDATE reliability_account SET held_minor = 0 WHERE account_id = 'SYN-DEBTOR'");
        assertThatThrownBy(() -> payments.review(pending.operationId(), "operator", "rail check", "SIM-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.get(pending.operationId()).state()).isEqualTo("INVESTIGATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reliability_investigation_audit WHERE operation_id = ?",
                Integer.class, pending.operationId())).isZero();

        jdbc.update("UPDATE reliability_account SET held_minor = 3500 WHERE account_id = 'SYN-DEBTOR'");
        PaymentView settled = payments.review(pending.operationId(), "operator", "rail check", "SIM-2");
        assertThat(settled.state()).isEqualTo("SETTLED");
        assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(96_500);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reliability_investigation_audit
                WHERE operation_id = ? AND prior_state = 'INVESTIGATION'
                  AND resulting_state = 'SETTLED' AND evidence_reference = 'SIM-2'
                """, Integer.class, pending.operationId())).isEqualTo(1);
    }

    @Test
    void uncorrelatedReplyEntersInvestigationWithoutFabricatedRejection() {
        Pacs008Message request = payment("E2E-MALFORMED", "TX-MALFORMED", "55.00");
        when(rail.submit(any(), anyString())).thenReturn(new RailObservation(
                "WRONG-TRANSACTION", request.getEndToEndId(), request.getMessageId(),
                "REJECTED", "SYNTHETIC_RAIL", "R-WRONG"));
        PaymentView result = payments.submit(request);
        assertThat(result.state()).isEqualTo("INVESTIGATION");
        assertThat(result.serviceStatus()).isNull();
        assertThat(payments.account("SYN-DEBTOR").heldMinor()).isEqualTo(5_500);
        assertThat(payments.effects(result.operationId()).stream()
                .map(ReliablePaymentService.EffectView::effectType)).containsExactly("HOLD");
    }

    @Test
    void preSendAckCrashWindowCanDispatchOnceFromDurableReservation() {
        payments.provisionSyntheticAccount("ASYNC-RECOVERY", 100_000, true, "ASYNC");
        Pacs008Message request = payment("E2E-PRESEND", "TX-PRESEND", "90.00");
        request.setDebtorAccountNumber("ASYNC-RECOVERY");
        when(rail.submit(any(), anyString())).thenReturn(status(request, "SETTLED", "R-PRESEND"));
        PaymentView corePending = payments.submit(request);
        jdbc.update("UPDATE reliability_core_ack SET status = 'ACKED' WHERE operation_id = ?",
                corePending.operationId());
        jdbc.update("UPDATE reliability_payment SET state = 'RESERVED' WHERE operation_id = ?",
                corePending.operationId());

        payments.dispatchRecoverable(20);
        payments.dispatchRecoverable(20);
        assertThat(payments.get(corePending.operationId()).state()).isEqualTo("SETTLED");
        assertThat(payments.account("ASYNC-RECOVERY").ledgerMinor()).isEqualTo(91_000);
        verify(rail, times(1)).submit(any(), anyString());
    }

    @Test
    void concurrentSameBusinessKeyCreatesOneHoldAndOneSend() throws Exception {
        Pacs008Message request = payment("E2E-RACE", "TX-RACE", "300.00");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(rail.submit(any(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return status(request, "SETTLED", "R-RACE");
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> payments.submit(request));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> payments.submit(request));
            PaymentView secondView = second.get(5, TimeUnit.SECONDS);
            assertThat(secondView.state()).isIn("SUBMITTING", "SETTLED");
            release.countDown();
            PaymentView firstView = first.get(5, TimeUnit.SECONDS);
            assertThat(firstView.operationId()).isEqualTo(secondView.operationId());
            assertThat(payments.account("SYN-DEBTOR").ledgerMinor()).isEqualTo(70_000);
            verify(rail, times(1)).submit(any(), anyString());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static Pacs008Message payment(String key, String transaction, String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transaction).creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1).endToEndId(key).transactionId(transaction)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("SYN-DEBTOR").creditorAccountNumber("SYN-CREDITOR")
                .debtorName("Synthetic Sender").creditorName("Synthetic Receiver").build();
    }

    private static RailObservation status(Pacs008Message payment, String value, String ref) {
        return new RailObservation(payment.getTransactionId(), payment.getEndToEndId(),
                payment.getMessageId(), value, "SYNTHETIC_RAIL", ref);
    }
}
