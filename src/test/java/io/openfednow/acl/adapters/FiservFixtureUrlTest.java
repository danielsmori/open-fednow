package io.openfednow.acl.adapters;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FiservFixtureUrlTest {
    @Test
    void acceptsOnlyLoopbackFixtureHosts() {
        assertThat(FiservAdapter.isLoopbackFixtureUrl("http://localhost:8089")).isTrue();
        assertThat(FiservAdapter.isLoopbackFixtureUrl("http://127.0.0.1:8089")).isTrue();
        assertThat(FiservAdapter.isLoopbackFixtureUrl("https://bankinghub-cert.fiservapis.com")).isFalse();
        assertThat(FiservAdapter.isLoopbackFixtureUrl("http://localhost.attacker.example")).isFalse();
        assertThat(FiservAdapter.isLoopbackFixtureUrl("not-a-url")).isFalse();
    }
}
