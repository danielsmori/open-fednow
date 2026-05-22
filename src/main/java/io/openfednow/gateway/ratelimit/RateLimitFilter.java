package io.openfednow.gateway.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Per-client rate limiting on the FedNow and RTP transfer endpoints.
 *
 * <p>Protects the gateway from a misconfigured upstream — a runaway core
 * banking adapter or a load-test left running could otherwise flood the
 * gateway, exhaust the thread pool, and (worse) trigger unnecessary
 * downstream FedNow / TCH submissions.
 *
 * <h2>Scope</h2>
 * Applies to {@code POST /fednow/receive}, {@code POST /fednow/send},
 * {@code POST /rtp/receive}, and {@code POST /rtp/send}. Other paths
 * (health checks, the admin namespace, actuators) are not rate limited.
 *
 * <h2>Algorithm</h2>
 * One Resilience4j {@link RateLimiter} per client identity, lazily created
 * by {@link RateLimiterRegistry}. Client identity is the
 * {@code X-Forwarded-For} header when present (split on commas, first token —
 * the original caller behind a proxy), otherwise the remote address. Each
 * client gets {@code openfednow.rate-limit.transfers-per-second} permits per
 * one-second refresh window.
 *
 * <h2>Response on limit exceeded</h2>
 * The filter returns HTTP {@code 429 Too Many Requests} with a
 * {@code Retry-After: 1} header (the refresh interval) and increments the
 * {@code gateway.rate_limited} Micrometer counter. The downstream
 * controller is never invoked.
 *
 * <h2>Ordering</h2>
 * Mounted at {@code HIGHEST_PRECEDENCE + 20}, so it runs after
 * {@link io.openfednow.gateway.CorrelationFilter} (which sets MDC) and the
 * admin audit filter, but before Spring Security. Rate limiting must happen
 * before authentication so an unauthenticated flood doesn't consume the
 * auth machinery.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String RATE_LIMITED_METRIC = "gateway.rate_limited";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimiterRegistry rateLimiterRegistry;
    private final Counter rateLimitedCounter;
    private final int transfersPerSecond;

    public RateLimitFilter(
            MeterRegistry meterRegistry,
            @Value("${openfednow.rate-limit.transfers-per-second:100}") int transfersPerSecond) {
        if (transfersPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.rate-limit.transfers-per-second must be positive");
        }
        this.transfersPerSecond = transfersPerSecond;
        this.rateLimiterRegistry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitForPeriod(transfersPerSecond)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        // tryAcquirePermission must not block — return immediately so
                        // the caller sees 429 rather than experiencing latency
                        .timeoutDuration(Duration.ZERO)
                        .build());
        this.rateLimitedCounter = Counter.builder(RATE_LIMITED_METRIC)
                .description("Number of requests rejected by the gateway rate limiter")
                .register(meterRegistry);
    }

    int getTransfersPerSecond() {
        return transfersPerSecond;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !(uri.startsWith("/fednow/") || uri.startsWith("/rtp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientId = resolveClientId(request);
        RateLimiter limiter = rateLimiterRegistry.rateLimiter(clientId);

        if (!limiter.acquirePermission()) {
            rateLimitedCounter.increment();
            log.warn("Rate limit exceeded clientId={} path={} limitPerSecond={}",
                    clientId, request.getRequestURI(), transfersPerSecond);
            response.setStatus(429);
            response.setHeader(RETRY_AFTER_HEADER, "1");
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limit_exceeded\",\"limitPerSecond\":"
                            + transfersPerSecond + "}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolves the client identity used to key the per-client limiter.
     *
     * <p>Honors {@code X-Forwarded-For} when present so that a deployment
     * behind a load balancer or ingress sees real per-client buckets rather
     * than a single shared bucket keyed to the proxy. Falls back to
     * {@code request.getRemoteAddr()}.
     */
    static String resolveClientId(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For is a comma-separated chain; the first entry is the
            // original client. Subsequent entries are intermediate proxies.
            int comma = forwarded.indexOf(',');
            String first = comma < 0 ? forwarded : forwarded.substring(0, comma);
            return first.trim();
        }
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }
}
