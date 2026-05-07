package io.openfednow.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that seeds the SLF4J MDC with correlation IDs at the start
 * of every HTTP request and clears them on completion.
 *
 * <h2>MDC keys populated</h2>
 * <dl>
 *   <dt>{@value #MDC_REQUEST_ID}</dt>
 *   <dd>A UUID generated fresh for every request. Allows log lines from a
 *       single HTTP exchange to be correlated even without an ISO 20022 ID.</dd>
 *   <dt>{@value #MDC_END_TO_END_ID}</dt>
 *   <dd>ISO 20022 EndToEndId extracted from the {@value #HEADER_END_TO_END_ID}
 *       request header, if present. For inbound FedNow calls that don't carry
 *       this header, {@link MessageRouter} populates it from the parsed pacs.008
 *       body instead.</dd>
 * </dl>
 *
 * <p>The filter runs at the highest precedence so that all downstream filters,
 * interceptors, and application code can rely on the MDC being populated.
 * MDC is cleared in a {@code finally} block, ensuring it is removed even when
 * a downstream component throws.
 *
 * <p><strong>Async boundaries:</strong> Spring's MDC context is thread-local.
 * If {@code @Async} methods or RabbitMQ listeners are introduced, an
 * {@code MdcTaskDecorator} should be wired into the relevant thread pools to
 * propagate these values across thread handoffs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    /** MDC key for the per-request UUID. */
    public static final String MDC_REQUEST_ID = "requestId";

    /** MDC key for the ISO 20022 EndToEndId. */
    public static final String MDC_END_TO_END_ID = "endToEndId";

    /** MDC key for the ISO 20022 TransactionId (set by MessageRouter, not this filter). */
    public static final String MDC_TRANSACTION_ID = "transactionId";

    /** Request header from which the EndToEndId is read. */
    public static final String HEADER_END_TO_END_ID = "X-End-To-End-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            MDC.put(MDC_REQUEST_ID, UUID.randomUUID().toString());

            String endToEndId = request.getHeader(HEADER_END_TO_END_ID);
            if (endToEndId != null && !endToEndId.isBlank()) {
                MDC.put(MDC_END_TO_END_ID, endToEndId);
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
