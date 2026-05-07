package io.openfednow.gateway;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CorrelationFilter}.
 *
 * <p>No Spring context is needed — the filter is instantiated directly and
 * invoked with Spring's {@link MockHttpServletRequest} / {@link MockHttpServletResponse}.
 * MDC state is verified both during and after request processing.
 */
class CorrelationFilterTest {

    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void clearMdc() {
        // Belt-and-suspenders: ensure no MDC leaks between tests
        MDC.clear();
    }

    // --- requestId is always set ---

    @Test
    void requestIdIsSetInMdcDuringRequest() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> captured.set(MDC.get(CorrelationFilter.MDC_REQUEST_ID)));

        assertThat(captured.get())
                .as("requestId should be a non-blank UUID")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void eachRequestGetsUniqueRequestId() throws Exception {
        List<String> ids = new ArrayList<>();
        FilterChain captureId = (req, res) -> ids.add(MDC.get(CorrelationFilter.MDC_REQUEST_ID));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), captureId);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), captureId);

        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
    }

    // --- endToEndId from header ---

    @Test
    void endToEndIdHeaderIsPopulatedInMdc() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER_END_TO_END_ID, "E2E-TEST-001");

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> captured.set(MDC.get(CorrelationFilter.MDC_END_TO_END_ID)));

        assertThat(captured.get()).isEqualTo("E2E-TEST-001");
    }

    @Test
    void endToEndIdIsNotSetWhenHeaderIsAbsent() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>("sentinel");

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> captured.set(MDC.get(CorrelationFilter.MDC_END_TO_END_ID)));

        assertThat(captured.get())
                .as("endToEndId MDC key should not be set when the header is absent")
                .isNull();
    }

    @Test
    void blankEndToEndIdHeaderIsIgnored() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>("sentinel");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER_END_TO_END_ID, "   ");

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> captured.set(MDC.get(CorrelationFilter.MDC_END_TO_END_ID)));

        assertThat(captured.get()).isNull();
    }

    // --- MDC is always cleared after the request ---

    @Test
    void mdcIsClearedAfterSuccessfulRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER_END_TO_END_ID, "E2E-CLEAR-001");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(MDC.get(CorrelationFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(CorrelationFilter.MDC_END_TO_END_ID)).isNull();
    }

    @Test
    void mdcIsClearedEvenWhenFilterChainThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER_END_TO_END_ID, "E2E-ERR-001");

        assertThatThrownBy(() ->
                filter.doFilter(request, new MockHttpServletResponse(),
                        (req, res) -> { throw new RuntimeException("downstream error"); }))
                .isInstanceOf(RuntimeException.class);

        assertThat(MDC.get(CorrelationFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(CorrelationFilter.MDC_END_TO_END_ID)).isNull();
    }
}
