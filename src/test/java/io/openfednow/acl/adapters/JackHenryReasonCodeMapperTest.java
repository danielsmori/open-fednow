package io.openfednow.acl.adapters;

import io.openfednow.acl.adapters.jackhenry.JackHenryReasonCodeMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JackHenryReasonCodeMapper}.
 *
 * <p>Verifies that all documented jXchange numeric error codes map to the correct
 * ISO 20022 reason codes, and that unknown codes fall back to {@code NARR}.
 */
class JackHenryReasonCodeMapperTest {

    @Test
    void insufficientFunds_mapsToAM04() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3050")).isEqualTo("AM04");
    }

    @Test
    void closedAccount_mapsToAC04() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3051")).isEqualTo("AC04");
    }

    @Test
    void blockedAccount_mapsToAC06() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3052")).isEqualTo("AC06");
    }

    @Test
    void incorrectAccountNumber_mapsToAC01() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3053")).isEqualTo("AC01");
    }

    @Test
    void duplicatePayment_mapsToDUPL() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3054")).isEqualTo("DUPL");
    }

    @Test
    void amountExceedsLimit_mapsToAM14() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3055")).isEqualTo("AM14");
    }

    @Test
    void notAllowedAmount_mapsToAM02() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3056")).isEqualTo("AM02");
    }

    @Test
    void invalidRoutingNumber_mapsToRC01() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("3057")).isEqualTo("RC01");
    }

    @Test
    void unknownCode_mapsToNARR() {
        assertThat(JackHenryReasonCodeMapper.toIso20022("9999")).isEqualTo("NARR");
        assertThat(JackHenryReasonCodeMapper.toIso20022("0")).isEqualTo("NARR");
    }

    @Test
    void nullCode_mapsToNARR() {
        assertThat(JackHenryReasonCodeMapper.toIso20022(null)).isEqualTo("NARR");
    }

    @Test
    void isPosted_posted_returnsTrue() {
        assertThat(JackHenryReasonCodeMapper.isPosted("POSTED")).isTrue();
    }

    @Test
    void isPosted_approved_returnsTrue() {
        assertThat(JackHenryReasonCodeMapper.isPosted("APPROVED")).isTrue();
    }

    @Test
    void isPosted_pending_returnsFalse() {
        assertThat(JackHenryReasonCodeMapper.isPosted("PENDING")).isFalse();
    }

    @Test
    void isPosted_null_returnsFalse() {
        assertThat(JackHenryReasonCodeMapper.isPosted(null)).isFalse();
    }
}
