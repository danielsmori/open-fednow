package io.openfednow.acl.adapters.jackhenry;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Manages OAuth 2.0 access tokens for the Jack Henry jXchange Service Gateway.
 *
 * <p>Jack Henry's jXchange platform transitioned from legacy WS-Security
 * username/password authentication to OAuth 2.0 (effective May 2025). This
 * manager implements the client credentials grant with HTTP Basic auth: the
 * client ID and secret are sent as a Base64-encoded {@code Authorization} header
 * on the token request, identical to the pattern used by FIS Code Connect.
 *
 * <p>Tokens are cached in memory and refreshed proactively before the expiry
 * buffer window elapses. All public methods are thread-safe via {@code synchronized}.
 *
 * <h2>Token endpoint</h2>
 * <pre>
 *   POST {base-url}/oauth2/token
 *   Content-Type: application/x-www-form-urlencoded
 *   Authorization: Basic base64({clientId}:{clientSecret})
 *
 *   grant_type=client_credentials&amp;scope=https%3A%2F%2Fjackhenry.com%2Fjx%2Fservice-gateway.write
 * </pre>
 *
 * <h2>Production note</h2>
 * <p>Some Jack Henry production environments require signed JWT client assertions
 * (RFC 7523, grant type {@code urn:ietf:params:oauth:grant-type:jwt-bearer}).
 * Contact Jack Henry (vendorqa@jackhenry.com) for institution-specific credentials
 * and the exact OAuth configuration for your environment.
 *
 * @see <a href="https://jackhenry.dev/open-enterprise-api-docs/enterprise-soap-api/">
 *      Jack Henry jXchange SOAP API — Authentication</a>
 */
public class JackHenryTokenManager {

    private static final Logger log = LoggerFactory.getLogger(JackHenryTokenManager.class);

    /** OAuth 2.0 scope required to call the jXchange Service Gateway. */
    public static final String JXCHANGE_SCOPE = "https://jackhenry.com/jx/service-gateway.write";

    private final RestClient restClient;
    private final String tokenPath;
    private final String clientId;
    private final String clientSecret;
    private final long expiryBufferSeconds;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * @param restClient          configured with the jXchange OAuth base URL
     * @param tokenPath           token endpoint path (e.g. {@code /oauth2/token})
     * @param clientId            OAuth client ID issued by Jack Henry
     * @param clientSecret        OAuth client secret issued by Jack Henry
     * @param expiryBufferSeconds seconds before token expiry to treat it as stale and refresh
     */
    public JackHenryTokenManager(RestClient restClient, String tokenPath,
                                  String clientId, String clientSecret,
                                  long expiryBufferSeconds) {
        this.restClient = restClient;
        this.tokenPath = tokenPath;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.expiryBufferSeconds = expiryBufferSeconds;
    }

    /**
     * Returns a valid access token, fetching a new one if the current token is
     * absent or within the expiry buffer window.
     *
     * @return Bearer token string
     * @throws JackHenryAuthException if the token endpoint returns an empty or invalid response
     */
    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        return fetchToken();
    }

    /** Invalidates the cached token, forcing a fresh fetch on the next call. */
    public synchronized void invalidate() {
        cachedToken = null;
        tokenExpiry = Instant.EPOCH;
    }

    private String fetchToken() {
        log.debug("Fetching Jack Henry jXchange OAuth token from {}", tokenPath);

        // Jack Henry uses HTTP Basic auth for token requests (client_id:secret Base64-encoded),
        // the same pattern as FIS Code Connect. This is distinct from Fiserv, which posts
        // credentials as form body parameters.
        String credentials = clientId + ":" + clientSecret;
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", JXCHANGE_SCOPE);

        TokenResponse response = restClient.post()
                .uri(tokenPath)
                .header("Authorization", basicAuth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new JackHenryAuthException(
                    "Jack Henry jXchange OAuth token response was null or missing access_token");
        }

        cachedToken = response.accessToken();
        tokenExpiry = Instant.now()
                .plusSeconds(response.expiresIn())
                .minusSeconds(expiryBufferSeconds);

        log.debug("Jack Henry jXchange OAuth token obtained; expires in {}s", response.expiresIn());
        return cachedToken;
    }

    /**
     * JSON response shape returned by the Jack Henry OAuth token endpoint.
     * Field names follow the OAuth 2.0 RFC 6749 specification.
     */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type")   String tokenType,
            @JsonProperty("expires_in")   long expiresIn
    ) {}
}
