package io.openfednow.security.pii;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PiiRedactor}. The redactor is the single source of
 * truth for what does and doesn't get masked; the whole point of a central
 * utility is that this test file is the one place where policy changes
 * become visible.
 */
class PiiRedactorTest {

    // ── maskAccount ──────────────────────────────────────────────────────────

    @Test
    void maskAccountKeepsLastFourAndMasksTheRest() {
        assertThat(PiiRedactor.maskAccount("1234567890")).isEqualTo("******7890");
    }

    @Test
    void maskAccountOfExactlyFourCharactersMasksEverything() {
        // If we revealed all 4, the redaction would be a no-op.
        assertThat(PiiRedactor.maskAccount("1234")).isEqualTo("****");
    }

    @Test
    void maskAccountOfFewerThanFourCharactersMasksEverything() {
        assertThat(PiiRedactor.maskAccount("42")).isEqualTo("**");
    }

    @Test
    void maskAccountPassesThroughNull() {
        assertThat(PiiRedactor.maskAccount(null)).isNull();
    }

    @Test
    void maskAccountPassesThroughBlank() {
        assertThat(PiiRedactor.maskAccount("")).isEmpty();
        assertThat(PiiRedactor.maskAccount("   ")).isEqualTo("   ");
    }

    // ── redactQueryString ────────────────────────────────────────────────────

    @Test
    void redactQueryStringLeavesNonSensitiveParametersUntouched() {
        String query = "limit=50&offset=0&status=ACTIVE";
        assertThat(PiiRedactor.redactQueryString(query)).isEqualTo(query);
    }

    @Test
    void redactQueryStringMasksSensitiveParameterValues() {
        String query = "limit=50&token=abc123secretvalue&offset=0";
        assertThat(PiiRedactor.redactQueryString(query))
                .isEqualTo("limit=50&token=REDACTED&offset=0");
    }

    @Test
    void redactQueryStringHandlesMultipleSensitiveParameters() {
        String query = "apikey=k1&password=p1&limit=10";
        assertThat(PiiRedactor.redactQueryString(query))
                .isEqualTo("apikey=REDACTED&password=REDACTED&limit=10");
    }

    @Test
    void redactQueryStringMatchesParameterNamesCaseInsensitively() {
        // Real-world clients vary wildly on casing (Authorization, apiKey, ApiKey, ...)
        String query = "Authorization=bearer-xyz&ApiKey=v&Secret=hush";
        assertThat(PiiRedactor.redactQueryString(query))
                .isEqualTo("Authorization=REDACTED&ApiKey=REDACTED&Secret=REDACTED");
    }

    @Test
    void redactQueryStringLeavesEmptyValueUntouched() {
        // No secret to leak, so no rewrite — makes the redacted query easier to read
        String query = "token=&limit=1";
        assertThat(PiiRedactor.redactQueryString(query)).isEqualTo("token=&limit=1");
    }

    @Test
    void redactQueryStringLeavesBareKeyUntouched() {
        String query = "token&limit=1";
        assertThat(PiiRedactor.redactQueryString(query)).isEqualTo("token&limit=1");
    }

    @Test
    void redactQueryStringPreservesValueThatContainsEqualsCharacter() {
        // JWTs and base64 signatures often contain '=' — redact by the FIRST '=' only
        String query = "signature=abc=def=ghi";
        assertThat(PiiRedactor.redactQueryString(query)).isEqualTo("signature=REDACTED");
    }

    @Test
    void redactQueryStringPassesThroughNullAndEmpty() {
        assertThat(PiiRedactor.redactQueryString(null)).isNull();
        assertThat(PiiRedactor.redactQueryString("")).isEmpty();
    }

    // ── Consistency: redaction is idempotent ─────────────────────────────────

    @Test
    void redactQueryStringIsIdempotent() {
        // Re-redacting an already-redacted line must not further transform it
        String query = "token=leak&limit=10";
        String once = PiiRedactor.redactQueryString(query);
        String twice = PiiRedactor.redactQueryString(once);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void maskAccountIsIdempotent() {
        String masked = PiiRedactor.maskAccount("1234567890");
        assertThat(PiiRedactor.maskAccount(masked)).isEqualTo(masked);
    }
}
