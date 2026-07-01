package io.openfednow.gateway.signing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Verifies the {@code X-JWS-Signature} header on inbound FedNow requests.
 *
 * <p>FedNow signs the messages it sends us (pacs.002 responses inbound to our
 * outbound submissions, and other server-initiated messages). This filter runs
 * ahead of Spring's controller dispatch, buffers the request body once so the
 * downstream controller can still deserialize it, and calls
 * {@link FedNowJwsVerifier#verify(String, byte[])} against the raw bytes.
 *
 * <p>Scope: {@code /fednow/**} only. The RTP gateway does not use JWS —
 * TCH's message authentication rides on the dedicated private-network
 * connection and mTLS.
 *
 * <p>Deployment modes:
 * <ul>
 *   <li>Sandbox / dev — {@link FedNowJwsVerifier} bean is not created because
 *       {@code openfednow.fednow.signing.enabled=false}. This filter still
 *       loads (Spring injects an empty {@code Optional}) but every request is
 *       passed through unverified. The Ingress-level mTLS termination is the
 *       only inbound authentication in that mode, which matches the rest of
 *       the framework's sandbox posture.</li>
 *   <li>Production — verifier bean is active, filter enforces signature on
 *       every /fednow/** POST. Missing or invalid signatures produce
 *       {@code 401 Unauthorized}; the controller is never invoked.</li>
 * </ul>
 *
 * <p>Ordering: runs at {@code HIGHEST_PRECEDENCE + 30} — after
 * {@code CorrelationFilter} (MDC seeded), after {@code AdminAccessAuditFilter}
 * (unrelated to this path), and after {@code RateLimitFilter} so a signature
 * check is not spent on rate-limited traffic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class JwsInboundVerificationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwsInboundVerificationFilter.class);

    static final String JWS_HEADER = "X-JWS-Signature";
    static final String FEDNOW_PATH_PREFIX = "/fednow/";

    private final FedNowJwsVerifier verifier;

    /**
     * The verifier is optional so a sandbox / dev deployment without signing
     * enabled still loads cleanly. When the bean is absent (i.e., the
     * {@code openfednow.fednow.signing.enabled=true} condition on
     * {@link FedNowSigningConfig} did not fire), this filter is a no-op.
     */
    public JwsInboundVerificationFilter(
            @Autowired(required = false) FedNowJwsVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (verifier == null) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(FEDNOW_PATH_PREFIX)) {
            return true;
        }
        // Only POST paths carry JWS-signed bodies. Health / OPTIONS / GET have no
        // body to authenticate.
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Read the full body once so we can (a) verify against the exact bytes
        // FedNow signed, and (b) hand a fresh InputStream to the controller.
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());

        String jws = request.getHeader(JWS_HEADER);
        try {
            verifier.verify(jws, body);
        } catch (FedNowJwsVerifier.JwsVerificationException e) {
            log.warn("Inbound JWS verification failed uri={} reason={}",
                    request.getRequestURI(), e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"jws_verification_failed\",\"reason\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }

        // Verification passed — proceed with a wrapper that lets the controller
        // read the body again from the buffered bytes.
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Servlet request wrapper that serves the buffered body to any downstream
     * reader. Necessary because {@link javax.servlet.ServletInputStream} is
     * single-pass — the verifier already consumed the stream.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest original, byte[] body) {
            super(original);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffered = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return buffered.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    // Async reads not supported — the controller path uses blocking IO.
                }
                @Override public int read() { return buffered.read(); }
                @Override public int read(byte[] b, int off, int len) { return buffered.read(b, off, len); }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    new ByteArrayInputStream(body), java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
