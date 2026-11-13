package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How {@link AvailabilityBridge} drives automatic reconciliation — issue #68, PR 3.
 */
class AvailabilityBridgeReconciliationTest {

    private final CoreBankingAdapter core = mock(CoreBankingAdapter.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final AutomaticReconciliationTrigger trigger = mock(AutomaticReconciliationTrigger.class);
    private final AvailabilityBridge bridge =
            new AvailabilityBridge(core, mock(ShadowLedger.class), rabbit, trigger);

    @Test
    void everyOfflinePollMarksReconciliationPendingAndNeverTriggers() {
        when(core.isCoreSystemAvailable()).thenReturn(false);

        bridge.pollCoreAvailability(); // transition
        bridge.pollCoreAvailability(); // still offline — re-marks in case a mark was lost

        verify(trigger, times(2)).onCoreOffline();
        verify(trigger, never()).onCoreOnline();
    }

    @Test
    void failingAvailabilityCheckCountsAsOffline() {
        when(core.isCoreSystemAvailable()).thenThrow(new RuntimeException("timeout"));

        bridge.pollCoreAvailability();

        verify(trigger).onCoreOffline();
        verify(trigger, never()).onCoreOnline();
    }

    @Test
    void everyOnlinePollHandsOverToTheTrigger() {
        when(core.isCoreSystemAvailable()).thenReturn(false, true, true);

        bridge.pollCoreAvailability();
        bridge.pollCoreAvailability(); // recovery
        bridge.pollCoreAvailability(); // still online — the trigger retries if still owed

        verify(trigger, times(2)).onCoreOnline();
        verify(trigger, times(1)).onCoreOffline();
    }

    @Test
    void inboundPaymentQueuedForTheCoreMarksReconciliationPending() {
        bridge.queueForCoreProcessing("E2E-1", "{}");

        verify(rabbit).convertAndSend(eq(RabbitMqConfig.MAINTENANCE_QUEUE), eq((Object) "{}"),
                any(MessagePostProcessor.class));
        verify(trigger).markPending(anyString());
    }

    @Test
    void paymentPathMarksGoThroughTheTrigger() {
        bridge.markReconciliationPending("bridge-mode send counted");

        verify(trigger).markPending("bridge-mode send counted");
    }
}
