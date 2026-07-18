package io.openfednow.shadowledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NoOpCardAuthorizationEventListenerTest {

    @Autowired
    private CardAuthorizationEventListener listener;

    @Test
    void noOpListenerIsAutoWiredByDefault() {
        assertThat(listener).isInstanceOf(NoOpCardAuthorizationEventListener.class);
    }
}
