package io.openfednow.gateway.signing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies {@link JwsInboundVerificationFilter} correctly gates FedNow POSTs
 * on a valid {@code X-JWS-Signature} and passes buffered bytes through to
 * the downstream controller.
 */
class JwsInboundVerificationFilterTest {

    private static KeyPair keyPair;
    private static PublicKey publicKey;
    private static final String KID = "filter-test-key";

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        publicKey = keyPair.getPublic();
    }

    // ── Path scoping ─────────────────────────────────────────────────────────

    @Test
    void nonFednowPathsAreNotFiltered() throws Exception {
        JwsInboundVerificationFilter filter = new JwsInboundVerificationFilter(newVerifier());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rtp/receive");
        request.setContent("body".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        // Chain called, no 401
        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void getRequestsToFednowAreNotFiltered() throws Exception {
        JwsInboundVerificationFilter filter = new JwsInboundVerificationFilter(newVerifier());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fednow/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void noVerifierMakesTheFilterANoOp() throws Exception {
        // Sandbox / dev mode — no FedNowJwsVerifier bean present. Every request passes.
        JwsInboundVerificationFilter filter = new JwsInboundVerificationFilter(null);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fednow/receive");
        request.setContent("body".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        // Chain called (i.e., filter passed through) and no 401 written
        verify(chain).doFilter(any(HttpServletRequest.class), any());
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    // ── Valid signature ──────────────────────────────────────────────────────

    @Test
    void validSignaturePassesAndBufferedBodyIsAvailableDownstream() throws Exception {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        byte[] body = "{\"messageId\":\"MSG-VALID\"}".getBytes(StandardCharsets.UTF_8);
        String jws = signer.sign(body);

        JwsInboundVerificationFilter filter = new JwsInboundVerificationFilter(newVerifier());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fednow/receive");
        request.setContent(body);
        request.addHeader("X-JWS-Signature", jws);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Real chain — filter passes control to a stub that reads the request body.
        // The controller downstream depends on the body being readable *after* the
        // filter consumed it for verification.
        ArgumentCaptor<HttpServletRequest> forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(forwarded.capture(), any());
        HttpServletRequest wrapped = forwarded.getValue();
        byte[] readAgain = wrapped.getInputStream().readAllBytes();
        assertThat(new String(readAgain, StandardCharsets.UTF_8))
                .isEqualTo(new String(body, StandardCharsets.UTF_8));
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    // ── Rejection paths ──────────────────────────────────────────────────────

    @Test
    void missingHeaderReturns401() throws Exception {
        JwsInboundVerificationFilter filter = new JwsInboundVerificationFilter(newVerifier());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fednow/receive");
        request.setContent("body".getBytes(StandardCharsets.UTF_8));
        // no X-JWS-Signature header
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("jws_verification_failed");
        // Controller must never have been invoked
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void tamperedBodyReturns401() throws Exception {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        String jws = signer.sign("original".getBytes(StandardCharsets.UTF_8));

        JwsInboundVerificationFilter filter = new JwsInboundVerificationFilter(newVerifier());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fednow/receive");
        // Body was signed as "original" but arrives as "tampered!"
        request.setContent("tampered!".getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-JWS-Signature", jws);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unknownKidReturns401() throws Exception {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), "some-other-kid");
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);
        String jws = signer.sign(body);

        // Verifier only knows the test kid; the incoming JWS uses a different one
        JwsInboundVerificationFilter filter = new JwsInboundVerificationFilter(newVerifier());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fednow/receive");
        request.setContent(body);
        request.addHeader("X-JWS-Signature", jws);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private FedNowJwsVerifier newVerifier() {
        return new FedNowJwsVerifier(kid -> KID.equals(kid) ? publicKey : null);
    }
}
