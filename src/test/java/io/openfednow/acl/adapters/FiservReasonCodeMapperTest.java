package io.openfednow.acl.adapters;

import io.openfednow.acl.adapters.fiserv.FiservReasonCodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FiservReasonCodeMapper}.
 */
class FiservReasonCodeMapperTest {

    @ParameterizedTest(name = "Fiserv \"{0}\" → ISO 20022 \"{1}\"")
    @CsvSource({
            "INSF,       AM04",
            "INVLD_ACCT, AC01",
            "CLSD_ACCT,  AC04",
            "ACCT_FRZN,  AC06",
            "DUPLC,      DUPL",
            "DAILY_LMT,  AM14",
            "TXN_LMT,    AM02",
            "INVLD_RTNG, RC01"
    })
    void knownCodes_mapToCorrectIso20022(String fiservCode, String expectedIso) {
        assertThat(FiservReasonCodeMapper.toIso20022(fiservCode.trim()))
                .isEqualTo(expectedIso.trim());
    }

    @Test
    void unknownCode_mapsToNarr() {
        assertThat(FiservReasonCodeMapper.toIso20022("SOME_UNKNOWN_CODE")).isEqualTo("NARR");
    }

    @Test
    void nullCode_mapsToNarr() {
        assertThat(FiservReasonCodeMapper.toIso20022(null)).isEqualTo("NARR");
    }

    @Test
    void isApproved_approved() {
        assertThat(FiservReasonCodeMapper.isApproved("APPROVED")).isTrue();
        assertThat(FiservReasonCodeMapper.isApproved("ACCEPTED")).isTrue();
    }

    @Test
    void isApproved_rejectedAndPending_returnFalse() {
        assertThat(FiservReasonCodeMapper.isApproved("REJECTED")).isFalse();
        assertThat(FiservReasonCodeMapper.isApproved("PENDING")).isFalse();
        assertThat(FiservReasonCodeMapper.isApproved(null)).isFalse();
    }
}
