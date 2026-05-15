package io.openfednow.acl.adapters.fis;

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
 * Manages FIS Code Connect OAuth 2.0 access tokens.
 *
 * <p>Implements the OAuth 2.0 client credentials grant as documented in the
 * FIS Code Connect authentication guide (codeconnect.fisglobal.com).
 * FIS differs from Fiserv in how credentials are sent: the consumer key and
 * consumer secret are transmitted as a Base64-encoded Basic authorization
 * header rather than as form body parameters.
 *
 * <p>Tokens are cached in memory and refreshed proactively before the expiry
 * buffer window elapses. All public methods are thread-safe via
 * {@code synchronized}.
 *
 * <h2>Token endpoint</h2>
 * <pre>
 *   POST {fis-base-url}/oauth2/v1/token
 *   Content-Type: application/x-www-form-urlencoded
 *   Authorization: Basic base64({consumerKey}:{consumerSecret})
 *
 *   grant_type=client_credentials
 * </pre>
 *
 * @see <a href="https://codeconnect.fisglobal.com/app/guides/authenticate">
 *      FIS Code Connect Authentication Guide</a>
 */
public class FisTokenManager {

    private static final Logger log = LoggerFactory.getLogger(FisTokenManager.class);

    private final RestClient restClient;
    private final String tokenPath;
    private final String consumerKey;
    private final String consumerSecret;
    private final long expiryBufferSeconds;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * @param restClient          pre-configured with the FIS Code Connect base URL
     * @param tokenPath           path of the token endpoint (e.g. {@code /oauth2/v1/token})
     * @param consumerKey         OAuth consumer key issued via the Code Connect portal
     * @param consumerSecret      OAuth consumer secret issued via the Code Connect portal
     * @param expiryBufferSeconds seconds before expiry to treat the token as stale and refresh
     */
    public FisTokenManager(RestClient restClient, String tokenPath,
                           String consumerKey, String consumerSecret,
                           long expiryBufferSeconds) {
        this.restClient = restClient;
        this.tokenPath = tokenPath;
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.expiryBufferSeconds = expiryBufferSeconds;
    }

    /**
     * Returns a valid access token, fetching a new one if the current token is
     * absent or within the expiry buffer window.
     *
     * @return Bearer token string
     * @throws FisAuthException if the token endpoint returns an empty or invalid response
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
        log.debug("Fetching FIS Code Connect OAuth token from {}", tokenPath);

        // FIS uses HTTP Basic auth (Base64-encoded key:secret) to authenticate
        // the token request — unlike Fiserv which sends credentials in the form body.
        String credentials = consumerKey + ":" + consumerSecret;
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        TokenResponse response = restClient.post()
                .uri(tokenPath)
                .header("Authorization", basicAuth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new FisAuthException(
                    "FIS Code Connect OAuth token response was null or missing access_token");
        }

        cachedToken = response.accessToken();
        tokenExpiry = Instant.now()
                .plusSeconds(response.expiresIn())
                .minusSeconds(expiryBufferSeconds);

        log.debug("FIS Code Connect OAuth token obtained; expires in {}s", response.expiresIn());
        return cachedToken;
    }

    /**
     * JSON response shape for the FIS Code Connect token endpoint.
     * Field names follow the OAuth 2.0 RFC 6749 specification.
     */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type")   String tokenType,
            @JsonProperty("expires_in")   long expiresIn
    ) {}
}
