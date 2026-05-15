package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Manages Fiserv OAuth 2.0 access tokens for the Communicator Open / DNA API.
 *
 * <p>Implements the OAuth 2.0 client credentials grant as documented in the
 * Fiserv Communicator Open API authentication guide (docs.fiserv.dev).
 * Tokens are cached in memory and refreshed proactively before the expiry
 * buffer window elapses.
 *
 * <p>All public methods are thread-safe via {@code synchronized}.
 *
 * <h2>Token endpoint</h2>
 * <pre>
 *   POST {fiserv-base-url}/fts-apim/oauth2/v2
 *   Content-Type: application/x-www-form-urlencoded
 *
 *   grant_type=client_credentials
 *   &amp;client_id={clientId}
 *   &amp;client_secret={clientSecret}
 * </pre>
 *
 * @see <a href="https://docs.fiserv.dev">Fiserv Developer Documentation</a>
 */
public class FiservTokenManager {

    private static final Logger log = LoggerFactory.getLogger(FiservTokenManager.class);

    private final RestClient restClient;
    private final String tokenPath;
    private final String clientId;
    private final String clientSecret;
    private final long expiryBufferSeconds;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * @param restClient          pre-configured with the Fiserv base URL
     * @param tokenPath           path of the token endpoint (e.g. {@code /fts-apim/oauth2/v2})
     * @param clientId            OAuth client ID issued by Fiserv
     * @param clientSecret        OAuth client secret issued by Fiserv
     * @param expiryBufferSeconds seconds before expiry to treat the token as stale and refresh
     */
    public FiservTokenManager(RestClient restClient, String tokenPath,
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
     * @throws FiservAuthException if the token endpoint returns an empty or invalid response
     */
    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        return fetchToken();
    }

    private String fetchToken() {
        log.debug("Fetching Fiserv OAuth token from {}", tokenPath);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        TokenResponse response = restClient.post()
                .uri(tokenPath)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new FiservAuthException("Fiserv OAuth token response was null or missing access_token");
        }

        cachedToken = response.accessToken();
        tokenExpiry = Instant.now()
                .plusSeconds(response.expiresIn())
                .minusSeconds(expiryBufferSeconds);

        log.debug("Fiserv OAuth token obtained; expires in {}s", response.expiresIn());
        return cachedToken;
    }

    /** Invalidates the cached token, forcing a fresh fetch on the next {@link #getAccessToken()} call. */
    public synchronized void invalidate() {
        cachedToken = null;
        tokenExpiry = Instant.EPOCH;
    }

    /**
     * JSON response shape for the Fiserv OAuth token endpoint.
     * Field names match the OAuth 2.0 RFC 6749 specification.
     */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type")   String tokenType,
            @JsonProperty("expires_in")   long expiresIn
    ) {}
}
