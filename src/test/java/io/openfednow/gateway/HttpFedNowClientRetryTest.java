package io.openfednow.gateway;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the retry behavior added to {@link HttpFedNowClient} — audit item #16.
 *
 * <p>Drives a WireMock-backed FedNow simulator with configurable failure /
 * recovery scenarios. The Retry policy is constructed locally per test so each
 * one exercises a specific shape of failure without depending on application
 * properties.
 */
@WireMockTest
class HttpFedNowClientRetryTest {

    private static final String TRANSFERS_PATH = HttpFedNowClient.TRANSFERS_PATH;

    // ── 5xx: retried until success ────────────────────────────────────────────

    @Test
    void serverErrorIsRetriedAndEventuallySucceeds(WireMockRuntimeInfo wmInfo) {
        // First two attempts return 503; the third returns a valid pacs.002
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .inScenario("retry-server-error")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-2"));
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .inScenario("retry-server-error")
                .whenScenarioStateIs("attempt-2")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("attempt-3"));
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .inScenario("retry-server-error")
                .whenScenarioStateIs("attempt-3")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(acscJson("E2E-001", "TXN-001"))));

        HttpFedNowClient client = newClient(wmInfo, retryOf3());
        Pacs002Message response = client.submitCreditTransfer(pacs008("E2E-001", "TXN-001"));

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        verify(3, postRequestedFor(urlPathEqualTo(TRANSFERS_PATH)));
    }

    @Test
    void serverErrorExhaustsRetriesAndReturnsSyntheticRjct(WireMockRuntimeInfo wmInfo) {
        // Every attempt returns 503 — the Retry exhausts, the client falls through
        // to the catch block and returns RJCT with the upstream status surfaced.
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .willReturn(aResponse().withStatus(503)));

        HttpFedNowClient client = newClient(wmInfo, retryOf3());
        Pacs002Message response = client.submitCreditTransfer(pacs008("E2E-EXH", "TXN-EXH"));

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getRejectReasonDescription()).contains("503");
        verify(3, postRequestedFor(urlPathEqualTo(TRANSFERS_PATH)));
    }

    // ── 4xx: NOT retried ──────────────────────────────────────────────────────

    @Test
    void clientErrorIsNotRetried(WireMockRuntimeInfo wmInfo) {
        // 400 Bad Request — a malformed message won't become well-formed by trying again.
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .willReturn(aResponse().withStatus(400)));

        HttpFedNowClient client = newClient(wmInfo, retryOf3());
        Pacs002Message response = client.submitCreditTransfer(pacs008("E2E-400", "TXN-400"));

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getRejectReasonDescription()).contains("400");
        // Only one attempt was made — 4xx is on the ignoreExceptions list
        verify(1, postRequestedFor(urlPathEqualTo(TRANSFERS_PATH)));
    }

    @Test
    void duplicateDetectionResponseIsNotRetried(WireMockRuntimeInfo wmInfo) {
        // 409 Conflict — FedNow signaling a duplicate. Retrying would not help; the
        // synthetic RJCT must surface the conflict to the saga.
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .willReturn(aResponse().withStatus(409)));

        HttpFedNowClient client = newClient(wmInfo, retryOf3());
        client.submitCreditTransfer(pacs008("E2E-409", "TXN-409"));

        verify(1, postRequestedFor(urlPathEqualTo(TRANSFERS_PATH)));
    }

    // ── No retry policy (null) — behaves as before ────────────────────────────

    @Test
    void clientWithNoRetryDoesNotRetryOn5xx(WireMockRuntimeInfo wmInfo) {
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .willReturn(aResponse().withStatus(503)));

        HttpFedNowClient client = new HttpFedNowClient(wmInfo.getHttpBaseUrl(), 5);  // legacy constructor
        Pacs002Message response = client.submitCreditTransfer(pacs008("E2E-NORE", "TXN-NORE"));

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        verify(1, postRequestedFor(urlPathEqualTo(TRANSFERS_PATH)));
    }

    // ── Success on first attempt — no retry overhead ──────────────────────────

    @Test
    void successfulSubmissionMakesExactlyOneRequest(WireMockRuntimeInfo wmInfo) {
        stubFor(post(urlPathEqualTo(TRANSFERS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(acscJson("E2E-OK", "TXN-OK"))));

        HttpFedNowClient client = newClient(wmInfo, retryOf3());
        Pacs002Message response = client.submitCreditTransfer(pacs008("E2E-OK", "TXN-OK"));

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        verify(1, postRequestedFor(urlPathEqualTo(TRANSFERS_PATH)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpFedNowClient newClient(WireMockRuntimeInfo wmInfo, Retry retry) {
        return new HttpFedNowClient(wmInfo.getHttpBaseUrl(), 5, retry);
    }

    private Retry retryOf3() {
        // Matches the production application.yml policy in shape: retry on transient
        // network failures + 5xx, ignore 4xx. Short waits for fast tests.
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        return Retry.of("test", config);
    }

    private Pacs008Message pacs008(String e2e, String txn) {
        return Pacs008Message.builder()
                .messageId("MSG-" + txn)
                .endToEndId(e2e)
                .transactionId(txn)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .creditorAccountNumber("ACC-001")
                .build();
    }

    private String acscJson(String e2e, String txn) {
        // Build a minimal pacs.002 ACSC body that the client's Jackson can deserialize.
        return """
                {
                  "messageId": "RES-%s",
                  "originalEndToEndId": "%s",
                  "originalTransactionId": "%s",
                  "transactionStatus": "ACSC",
                  "creationDateTime": "%s"
                }
                """.formatted(txn, e2e, txn, OffsetDateTime.now());
    }
}
