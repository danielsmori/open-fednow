package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openfednow.acl.core.ReliableOutboundCorePort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only Premier Trial adapter. An account response does not establish available funds. */
public final class PremierTrialAdapter implements ReliableOutboundCorePort {
    public static final String BASE_URL = "https://bankinghub-cert.fiservapis.com/banking/efx/v1";
    public static final String TOKEN_URL = "https://bankinghub-cert.fiservapis.com/fts-apim/oauth2/v2";
    private static final String ACCOUNT_PATH = "/acctservice/acctmgmt/accounts/secured";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public record TrialConfig(String baseUrl, String tokenUrl, String apiKey, String apiSecret,
                              String organizationId) {
        public TrialConfig {
            if (!BASE_URL.equals(baseUrl) || !TOKEN_URL.equals(tokenUrl))
                throw new IllegalArgumentException("Only the approved Banking Hub CERT destination is allowed");
            for (String value : new String[]{apiKey, apiSecret, organizationId}) {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Trial configuration is incomplete");
            }
            if (apiKey.contains(":")) throw new IllegalArgumentException("Invalid Basic authentication key");
        }
    }

    /** No account values or balances are exposed to the console or evidence bundle. */
    public record AccountObservation(boolean accountIdMatched, int balanceEntryCount) {}

    @FunctionalInterface
    interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    record Response(int status, String body) {}

    private final TrialConfig config;
    private final Transport transport;
    private final ObjectMapper mapper;

    public PremierTrialAdapter(TrialConfig config) {
        this(config, networkTransport(), new ObjectMapper());
    }

    PremierTrialAdapter(TrialConfig config, Transport transport, ObjectMapper mapper) {
        this.config = Objects.requireNonNull(config);
        this.transport = Objects.requireNonNull(transport);
        this.mapper = Objects.requireNonNull(mapper);
    }

    private static Transport networkTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            HttpResponse<String> result = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(result.statusCode(), result.body());
        };
    }

    public AccountObservation inspectDda(String accountId) throws IOException, InterruptedException {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("Sample DDA is required");
        String token = token();
        String header = mapper.writeValueAsString(Map.of(
                "OrganizationId", config.organizationId(), "TrnId", UUID.randomUUID().toString()));
        String body = mapper.writeValueAsString(Map.of("AcctSel", Map.of("AcctKeys", Map.of(
                "AcctId", accountId, "AcctType", "DDA"))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + ACCOUNT_PATH))
                .timeout(TIMEOUT).header("Authorization", "Bearer " + token)
                .header("EFXHeader", header).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonNode response = parseSuccessful(transport.send(request));
        JsonNode record = response.path("AcctRec");
        JsonNode balances = record.path("DepositAcctInfo").path("AcctBal");
        if (!accountId.equals(record.path("AcctKeys").path("AcctId").asText())
                || !balances.isArray() || balances.isEmpty())
            throw new IOException("Trial account response failed identity or balance-entry validation");
        return new AccountObservation(true, balances.size());
    }

    private String token() throws IOException, InterruptedException {
        String basic = java.util.Base64.getEncoder().encodeToString(
                (config.apiKey() + ":" + config.apiSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(TIMEOUT).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
        JsonNode response = parseSuccessful(transport.send(request));
        if (!"Bearer".equals(response.path("token_type").asText())
                || response.path("access_token").asText().isBlank())
            throw new IOException("Trial token response lacks a Bearer token");
        return response.path("access_token").asText();
    }

    private JsonNode parseSuccessful(Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300)
            throw new IOException("Banking Hub returned HTTP " + response.status());
        return mapper.readTree(response.body());
    }

    /** AcctBal entries have not been mapped to an authoritative available balance. */
    @Override public Optional<Balance> observeBalance(Identity identity) { return Optional.empty(); }
    @Override public Outcome reserve(Mutation request) { return Outcome.unsupported(request.identity()); }
    @Override public Outcome lookupReservation(Identity identity) { return Outcome.unsupported(identity); }
    @Override public Outcome postReservedDebit(Mutation request) { return Outcome.unsupported(request.identity()); }
    @Override public Outcome lookupPosting(Identity identity) { return Outcome.unsupported(identity); }
    @Override public Outcome releaseReservation(Identity identity, String reservationId) { return Outcome.unsupported(identity); }
    @Override public Outcome reversePosting(Identity identity, String postingId) { return Outcome.unsupported(identity); }
}
