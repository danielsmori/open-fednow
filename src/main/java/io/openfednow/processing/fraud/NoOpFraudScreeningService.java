package io.openfednow.processing.fraud;

import io.openfednow.iso20022.Pacs008Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Pass-through {@link FraudScreeningPort} active when no other implementation is registered.
 *
 * <p>The default-disabled posture matches the rest of the framework: a fresh
 * deployment runs without fraud screening unless {@code openfednow.fraud.enabled=true}
 * is set, at which point {@link DefaultFraudScreeningService} activates and this
 * bean drops out via {@link ConditionalOnMissingBean}.
 *
 * <p>This bean exists so that {@code MessageRouter} can depend on a non-null
 * {@link FraudScreeningPort} without conditional wiring. Every payment receives
 * a {@code PASS} until an institution explicitly opts in.
 */
@Component
@ConditionalOnMissingBean(FraudScreeningPort.class)
public class NoOpFraudScreeningService implements FraudScreeningPort {

    @Override
    public ScreeningResult screen(Pacs008Message message) {
        return ScreeningResult.pass();
    }
}
