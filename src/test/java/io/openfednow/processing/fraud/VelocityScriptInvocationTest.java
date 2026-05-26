package io.openfednow.processing.fraud;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that the velocity rule routes through the atomic Lua INCR+EXPIRE
 * script rather than the previous two-call sequence — audit item #4.
 *
 * <p>The Lua script combines the increment and the TTL set in a single Redis
 * round-trip so a mid-operation pod failure can't leave a TTL-less counter
 * behind. This test pins the contract: the service must call
 * {@code redis.execute(script, keys, args)} with the configured window, not
 * the legacy {@code opsForValue().increment()} + {@code expire()} pair.
 */
@SuppressWarnings("unchecked")
class VelocityScriptInvocationTest {

    @Test
    void velocityRuleInvokesLuaScriptOnce() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // No legacy ValueOperations stub — the new code path must not touch it.
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(1L);

        DefaultFraudScreeningService service = new DefaultFraudScreeningService(
                redis, new SimpleMeterRegistry(),
                new BigDecimal("1000000"),  // huge cap so amount/REVIEW don't fire
                10, 60, "");

        service.screen(payment("ACC-DEBTOR", "ACC-CREDITOR"));

        // Capture the args sent to redis.execute and confirm the shape
        ArgumentCaptor<List<String>> keysCaptor =
                (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

        verify(redis).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertThat(keysCaptor.getValue())
                .hasSize(1)
                .first().asString()
                .startsWith(DefaultFraudScreeningService.VELOCITY_KEY_PREFIX)
                .endsWith("ACC-DEBTOR");
        // The TTL arg (varargs) — the window seconds as a string
        assertThat(argsCaptor.getValue()).containsExactly("60");

        // Legacy path must not have been called
        verify(redis, never()).opsForValue();
        verify(redis, never()).expire(any(String.class), any());
    }

    @Test
    void velocityRuleSkippedWhenDebtorIsNull() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DefaultFraudScreeningService service = new DefaultFraudScreeningService(
                redis, new SimpleMeterRegistry(),
                new BigDecimal("1000000"), 10, 60, "");

        // No debtor — velocity check must be entirely bypassed
        Pacs008Message msg = Pacs008Message.builder()
                .endToEndId("E2E-NULL-DEBTOR")
                .transactionId("TXN-NULL-DEBTOR")
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(null)
                .creditorAccountNumber("ACC-OK")
                .build();
        service.screen(msg);

        verify(redis, never()).execute(any(RedisScript.class), any(List.class), any());
    }

    @Test
    void velocityCountAboveLimitBlocks() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // Lua script returns 11 — over the limit of 10
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(11L);

        DefaultFraudScreeningService service = new DefaultFraudScreeningService(
                redis, new SimpleMeterRegistry(),
                new BigDecimal("1000000"), 10, 60, "");

        ScreeningResult result = service.screen(payment("ACC-HOT", "ACC-OK"));

        assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.BLOCK);
        assertThat(result.description()).contains("debtor velocity 11 exceeds 10");
    }

    @Test
    void velocityCountAtLimitDoesNotBlock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(10L);

        DefaultFraudScreeningService service = new DefaultFraudScreeningService(
                redis, new SimpleMeterRegistry(),
                new BigDecimal("1000000"), 10, 60, "");

        ScreeningResult result = service.screen(payment("ACC-AT-LIMIT", "ACC-OK"));

        // Strictly greater than — count == limit is allowed
        assertThat(result.decision()).isNotEqualTo(ScreeningResult.Decision.BLOCK);
    }

    @Test
    void configuredWindowIsPassedAsScriptArgument() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(1L);

        DefaultFraudScreeningService service = new DefaultFraudScreeningService(
                redis, new SimpleMeterRegistry(),
                new BigDecimal("1000000"), 5, 120, "");  // 120-second window

        service.screen(payment("ACC-WIN", "ACC-OK"));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), eq(List.of(
                DefaultFraudScreeningService.VELOCITY_KEY_PREFIX + "ACC-WIN")),
                argsCaptor.capture());
        // Custom window made it through to the script
        assertThat(argsCaptor.getValue()).containsExactly("120");
    }

    private static Pacs008Message payment(String debtor, String creditor) {
        return Pacs008Message.builder()
                .messageId("MSG-" + debtor)
                .endToEndId("E2E-" + debtor)
                .transactionId("TXN-" + debtor)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(debtor)
                .creditorAccountNumber(creditor)
                .build();
    }
}
