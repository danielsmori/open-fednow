package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openfednow.acl.core.ReliableOutboundCorePort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PremierTrialAdapterTest {
    private final PremierTrialAdapter.TrialConfig config = new PremierTrialAdapter.TrialConfig(
            PremierTrialAdapter.BASE_URL, PremierTrialAdapter.TOKEN_URL, "synthetic-key",
            "synthetic-secret", "synthetic-org");

    @Test
    void requestsUsePremierContractAndKeepFinancialOperationsUnsupported() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        PremierTrialAdapter adapter = new PremierTrialAdapter(config, request -> {
            requests.add(request);
            if (requests.size() == 1)
                return new PremierTrialAdapter.Response(200,
                        "{\"token_type\":\"Bearer\",\"access_token\":\"synthetic-token\"}");
            return new PremierTrialAdapter.Response(200,
                    "{\"AcctRec\":{\"AcctKeys\":{\"AcctId\":\"sample\"}," +
                    "\"DepositAcctInfo\":{\"AcctBal\":[{},{}]}}}");
        }, new ObjectMapper());

        assertThat(adapter.inspectDda("sample"))
                .isEqualTo(new PremierTrialAdapter.AccountObservation(true, 2));
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).uri().toString()).isEqualTo(PremierTrialAdapter.TOKEN_URL);
        assertThat(requests.get(0).method()).isEqualTo("POST");
        assertThat(body(requests.get(0))).isEqualTo("grant_type=client_credentials");
        assertThat(requests.get(0).headers().firstValue("Authorization")).hasValueSatisfying(
                value -> assertThat(value).startsWith("Basic "));
        assertThat(requests.get(1).uri().toString()).isEqualTo(PremierTrialAdapter.BASE_URL
                + "/acctservice/acctmgmt/accounts/secured");
        assertThat(requests.get(1).headers().firstValue("Authorization")).contains("Bearer synthetic-token");
        assertThat(new ObjectMapper().readTree(body(requests.get(1))).path("AcctSel")
                .path("AcctKeys").path("AcctId").asText()).isEqualTo("sample");
        assertThat(requests.get(1).headers().firstValue("EFXHeader")).hasValueSatisfying(value -> {
            try {
                assertThat(new ObjectMapper().readTree(value).path("OrganizationId").asText())
                        .isEqualTo("synthetic-org");
            } catch (IOException error) { throw new AssertionError(error); }
        });
        ReliableOutboundCorePort.Identity identity = new ReliableOutboundCorePort.Identity("tenant", "sample", "key");
        assertThat(adapter.observeBalance(identity)).isEmpty();
        assertThat(adapter.reserve(new ReliableOutboundCorePort.Mutation(identity,
                java.math.BigDecimal.ONE, "USD", null)).status())
                .isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
        assertThat(adapter.lookupReservation(identity).status())
                .isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
    }

    @Test
    void mismatchedAccountAndEmptyBalancesAreRejected() {
        PremierTrialAdapter adapter = new PremierTrialAdapter(config, request -> {
            if (request.uri().toString().equals(PremierTrialAdapter.TOKEN_URL))
                return new PremierTrialAdapter.Response(200,
                        "{\"token_type\":\"Bearer\",\"access_token\":\"synthetic-token\"}");
            return new PremierTrialAdapter.Response(200,
                    "{\"AcctRec\":{\"AcctKeys\":{\"AcctId\":\"other\"}," +
                    "\"DepositAcctInfo\":{\"AcctBal\":[]}}}");
        }, new ObjectMapper());
        assertThatThrownBy(() -> adapter.inspectDda("sample"))
                .isInstanceOf(IOException.class).hasMessageNotContaining("other");
    }

    @Test
    void failedAuthenticationNeverReadsAnAccountOrPrintsProviderBody() {
        List<HttpRequest> requests = new ArrayList<>();
        PremierTrialAdapter adapter = new PremierTrialAdapter(config, request -> {
            requests.add(request);
            return new PremierTrialAdapter.Response(401, "private provider response");
        }, new ObjectMapper());
        assertThatThrownBy(() -> adapter.inspectDda("sample"))
                .isInstanceOf(IOException.class).hasMessageNotContaining("private provider response");
        assertThat(requests).hasSize(1);
    }

    @Test
    void onlyApprovedCertEndpointsAreAccepted() {
        assertThatThrownBy(() -> new PremierTrialAdapter.TrialConfig(
                "https://other.example", PremierTrialAdapter.TOKEN_URL, "key", "secret", "org"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String body(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { throw new AssertionError(error); }
            @Override public void onComplete() {}
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
