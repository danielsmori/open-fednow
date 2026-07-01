package io.openfednow.processing.fraud;

import io.openfednow.iso20022.Pacs008Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pass-through {@link FraudScreeningPort} active when no other implementation is registered.
 *
 * <p>The default-disabled posture matches the rest of the framework: a fresh
 * deployment runs without fraud screening unless {@code openfednow.fraud.enabled=true}
 * is set, at which point {@link DefaultFraudScreeningService} activates and this
 * NoOp drops out via the inverse property condition.
 *
 * <p>The pair uses {@code @ConditionalOnProperty} on both sides rather than
 * {@code @ConditionalOnMissingBean}: mixing {@code @Component} scanning with
 * {@code @ConditionalOnMissingBean} is documented as unreliable — the
 * scanner may evaluate the condition before the alternative bean's own
 * conditions have been resolved, leaving neither bean registered and the
 * downstream {@code MessageRouter} without a FraudScreeningPort at all.
 *
 * <p>This bean exists so that {@code MessageRouter} can depend on a non-null
 * {@link FraudScreeningPort} without conditional wiring. Every payment receives
 * a {@code PASS} until an institution explicitly opts in.
 */
@Component
@ConditionalOnProperty(name = "openfednow.fraud.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpFraudScreeningService implements FraudScreeningPort {

    @Override
    public ScreeningResult screen(Pacs008Message message) {
        return ScreeningResult.pass();
    }
}
