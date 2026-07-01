package io.openfednow.gateway.signing;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.openfednow.gateway.HttpFedNowClient;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getAllServeEvents;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that {@link HttpFedNowClient}'s interceptor attaches the
 * {@code X-JWS-Signature} header and that {@link FedNowJwsVerifier} recognizes
 * it when driven against the same key pair — the receiver's side of the wire.
 *
 * <p>The WireMock stub returns a valid pacs.002; we don't verify FedNow's own
 * signed response here (that's covered by the filter test). What we check is:
 * (1) every outbound request carries the JWS header; (2) the header value
 * parses and verifies against the signer's public key over the exact body
 * bytes WireMock recorded.
 */
@WireMockTest
class JwsWireIntegrationTest {

    private static KeyPair keyPair;
    private static PublicKey publicKey;
    private static final String KID = "wire-test-key";

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        publicKey = keyPair.getPublic();
    }

    @Test
    void outboundRequestCarriesJwsHeader(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathEqualTo("/transfers"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "messageId": "RES-1",
                              "originalEndToEndId": "E2E-JWS-1",
                              "originalTransactionId": "TXN-JWS-1",
                              "transactionStatus": "ACSC",
                              "creationDateTime": "%s"
                            }
                            """.formatted(OffsetDateTime.now()))));

        HttpFedNowClient client = new HttpFedNowClient(
                wm.getHttpBaseUrl(), 5, null,
                new FedNowJwsSigner(keyPair.getPrivate(), KID));

        client.submitCreditTransfer(pacs008("E2E-JWS-1", "TXN-JWS-1"));

        RequestPatternBuilder pattern = postRequestedFor(urlPathEqualTo("/transfers"));
        verify(pattern);

        // Extract the exact body WireMock received and confirm the JWS verifies against it
        var event = getAllServeEvents().get(0);
        String jws = event.getRequest().getHeader("X-JWS-Signature");
        assertThat(jws).isNotNull();

        byte[] body = event.getRequest().getBody();
        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> KID.equals(kid) ? publicKey : null);
        verifier.verify(jws, body);  // must not throw
    }

    @Test
    void clientWithoutSignerSendsUnsignedRequest(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathEqualTo("/transfers"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "messageId": "RES-2",
                              "originalEndToEndId": "E2E-JWS-2",
                              "originalTransactionId": "TXN-JWS-2",
                              "transactionStatus": "ACSC",
                              "creationDateTime": "%s"
                            }
                            """.formatted(OffsetDateTime.now()))));

        // No signer → no interceptor → no header
        HttpFedNowClient client = new HttpFedNowClient(wm.getHttpBaseUrl(), 5);
        client.submitCreditTransfer(pacs008("E2E-JWS-2", "TXN-JWS-2"));

        var event = getAllServeEvents().get(0);
        assertThat(event.getRequest().getHeader("X-JWS-Signature")).isNull();
    }

    private Pacs008Message pacs008(String e2e, String txn) {
        return Pacs008Message.builder()
                .messageId("MSG-" + txn)
                .endToEndId(e2e)
                .transactionId(txn)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .creditorAccountNumber("ACC-1")
                .build();
    }

    /** Package-visible so the filter test can share the same keypair without re-generating. */
    static PublicKey testPublicKey() { return publicKey; }
    static KeyPair testKeyPair() { return keyPair; }
    static String testKid() { return KID; }
}
