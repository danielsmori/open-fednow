package io.openfednow.gateway.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link RateLimitFilter} — issue #46.
 *
 * <p>A {@link SimpleMeterRegistry} is used so counter assertions verify the
 * actual Micrometer wiring. The filter is instantiated directly with a
 * configurable {@code transfersPerSecond} so per-test limits stay isolated.
 */
class RateLimitFilterTest {

    // ── Construction validation ──────────────────────────────────────────────

    @Test
    void zeroPermitsRejectedAtConstruction() {
        assertThatThrownBy(() -> new RateLimitFilter(new SimpleMeterRegistry(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfers-per-second");
    }

    @Test
    void negativePermitsRejectedAtConstruction() {
        assertThatThrownBy(() -> new RateLimitFilter(new SimpleMeterRegistry(), -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfers-per-second");
    }

    // ── Scope filter (shouldNotFilter) ───────────────────────────────────────

    @Test
    void healthEndpointsAreNotRateLimited() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        // Exhaust the single permit
        runRequest(filter, "POST", "/fednow/receive", "1.2.3.4");
        // Same client hits health — must pass even though the bucket is empty
        MockHttpServletResponse response = runRequest(filter, "GET", "/fednow/health", "1.2.3.4");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void adminEndpointsAreNotRateLimited() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        // Two POSTs to /admin in the same second — both must pass through
        runRequest(filter, "POST", "/admin/reconcile", "1.2.3.4");
        MockHttpServletResponse response = runRequest(filter, "POST", "/admin/reconcile", "1.2.3.4");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(meterRegistry.counter(RateLimitFilter.RATE_LIMITED_METRIC).count()).isZero();
    }

    @Test
    void getMethodsAreNotRateLimitedEvenOnTransferPaths() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        // GET /fednow/health should not consume a permit
        runRequest(filter, "GET", "/fednow/health", "1.2.3.4");
        // POST should still get its full bucket
        MockHttpServletResponse response = runRequest(filter, "POST", "/fednow/receive", "1.2.3.4");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ── Within-limit behavior ────────────────────────────────────────────────

    @Test
    void requestsWithinLimitPassThroughToChain() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 5);

        MockHttpServletResponse response = runRequest(filter, "POST", "/fednow/receive", "1.2.3.4");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(meterRegistry.counter(RateLimitFilter.RATE_LIMITED_METRIC).count()).isZero();
    }

    @Test
    void multipleRequestsBelowLimitAllSucceed() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 5);

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = runRequest(filter, "POST", "/fednow/receive", "1.2.3.4");
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(meterRegistry.counter(RateLimitFilter.RATE_LIMITED_METRIC).count()).isZero();
    }

    // ── Limit-exceeded behavior ──────────────────────────────────────────────

    @Test
    void requestBeyondLimitReturns429WithRetryAfterHeader() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        runRequest(filter, "POST", "/fednow/receive", "1.2.3.4"); // consumes the only permit
        MockHttpServletResponse rejected = runRequest(filter, "POST", "/fednow/receive", "1.2.3.4");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader(RateLimitFilter.RETRY_AFTER_HEADER)).isEqualTo("1");
        assertThat(rejected.getContentType()).contains("application/json");
        assertThat(rejected.getContentAsString()).contains("rate_limit_exceeded");
    }

    @Test
    void counterIncrementsOncePerRejectedRequest() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        runRequest(filter, "POST", "/fednow/receive", "1.2.3.4");
        runRequest(filter, "POST", "/fednow/receive", "1.2.3.4"); // 1st rejected
        runRequest(filter, "POST", "/fednow/receive", "1.2.3.4"); // 2nd rejected
        runRequest(filter, "POST", "/fednow/receive", "1.2.3.4"); // 3rd rejected

        assertThat(meterRegistry.counter(RateLimitFilter.RATE_LIMITED_METRIC).count()).isEqualTo(3.0);
    }

    @Test
    void rejectedRequestDoesNotInvokeDownstreamChain() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        // Consume the permit with the first call (real chain)
        runRequest(filter, "POST", "/fednow/receive", "1.2.3.4");

        // Second call uses a mocked chain so we can verify it's never invoked
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/fednow/receive");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain mockChain = mock(FilterChain.class);

        filter.doFilter(req, res, mockChain);

        verify(mockChain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(429);
    }

    // ── Per-client isolation ─────────────────────────────────────────────────

    @Test
    void differentClientsHaveIndependentBuckets() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        // Client A consumes its single permit and is then rate limited
        runRequest(filter, "POST", "/fednow/receive", "1.1.1.1");
        MockHttpServletResponse aBlocked = runRequest(filter, "POST", "/fednow/receive", "1.1.1.1");
        assertThat(aBlocked.getStatus()).isEqualTo(429);

        // Client B uses a different IP and must still have its full quota
        MockHttpServletResponse bSuccess = runRequest(filter, "POST", "/fednow/receive", "2.2.2.2");
        assertThat(bSuccess.getStatus()).isEqualTo(200);
    }

    // ── RTP path coverage ────────────────────────────────────────────────────

    @Test
    void rtpEndpointsAreAlsoRateLimited() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(meterRegistry, 1);

        runRequest(filter, "POST", "/rtp/send", "3.3.3.3");
        MockHttpServletResponse rejected = runRequest(filter, "POST", "/rtp/send", "3.3.3.3");
        assertThat(rejected.getStatus()).isEqualTo(429);
    }

    // ── X-Forwarded-For client resolution ────────────────────────────────────

    @Test
    void clientIdentityHonorsXForwardedForHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/fednow/receive");
        req.setRemoteAddr("10.0.0.1"); // proxy address
        req.addHeader("X-Forwarded-For", "5.6.7.8, 10.0.0.5");

        assertThat(RateLimitFilter.resolveClientId(req)).isEqualTo("5.6.7.8");
    }

    @Test
    void clientIdentityFallsBackToRemoteAddrWhenNoForwardedHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/fednow/receive");
        req.setRemoteAddr("10.0.0.1");

        assertThat(RateLimitFilter.resolveClientId(req)).isEqualTo("10.0.0.1");
    }

    @Test
    void clientIdentityFallsBackToUnknownWhenBothAreMissing() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/fednow/receive");
        req.setRemoteAddr(null);

        assertThat(RateLimitFilter.resolveClientId(req)).isEqualTo("unknown");
    }

    @Test
    void clientIdentityTrimsForwardedForToken() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/fednow/receive");
        req.addHeader("X-Forwarded-For", "  5.6.7.8  , 10.0.0.5");

        assertThat(RateLimitFilter.resolveClientId(req)).isEqualTo("5.6.7.8");
    }

    @Test
    void clientIdentityHandlesSingleForwardedForEntry() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/fednow/receive");
        req.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(RateLimitFilter.resolveClientId(req)).isEqualTo("9.9.9.9");
    }

    // ── Configured limit exposure ────────────────────────────────────────────

    @Test
    void getTransfersPerSecondExposesConfiguredValue() {
        RateLimitFilter filter = new RateLimitFilter(new SimpleMeterRegistry(), 250);
        assertThat(filter.getTransfersPerSecond()).isEqualTo(250);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private MockHttpServletResponse runRequest(RateLimitFilter filter,
                                                String method, String path, String remoteAddr)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
