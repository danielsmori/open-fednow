package io.openfednow.shadowledger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default no-op {@link CardAuthorizationEventListener}.
 *
 * <p>This keeps card authorization event handling opt-in. Institutions can set
 * {@code openfednow.card-authorization.enabled=true} and provide their own
 * listener backed by a card processor event stream.
 */
@Component
@ConditionalOnProperty(name = "openfednow.card-authorization.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpCardAuthorizationEventListener implements CardAuthorizationEventListener {

    @Override
    public void onAuthorization(String accountId, long amountCents, String authCode) {
        // Intentionally empty: card authorization integration is institution-specific.
    }

    @Override
    public void onReversal(String accountId, long amountCents, String originalAuthCode) {
        // Intentionally empty: card authorization integration is institution-specific.
    }
}
