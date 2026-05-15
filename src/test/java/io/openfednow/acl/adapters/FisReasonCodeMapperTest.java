package io.openfednow.acl.adapters;

import io.openfednow.acl.adapters.fis.FisReasonCodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FisReasonCodeMapper}.
 */
class FisReasonCodeMapperTest {

    @ParameterizedTest(name = "FIS \"{0}\" → ISO 20022 \"{1}\"")
    @CsvSource({
            "INSUFF_FUNDS,    AM04",
            "INVLD_ACCT,      AC01",
            "CLSD_ACCT,       AC04",
            "FRZN_ACCT,       AC06",
            "DUPE_TXN,        DUPL",
            "LMT_EXCDED,      AM14",
            "TXN_AMT_EXCDED,  AM02",
            "INVLD_RTTNG,     RC01"
    })
    void knownCodes_mapToCorrectIso20022(String fisCode, String expectedIso) {
        assertThat(FisReasonCodeMapper.toIso20022(fisCode.trim()))
                .isEqualTo(expectedIso.trim());
    }

    @Test
    void unknownCode_mapsToNarr() {
        assertThat(FisReasonCodeMapper.toIso20022("SOME_UNKNOWN_CODE")).isEqualTo("NARR");
    }

    @Test
    void nullCode_mapsToNarr() {
        assertThat(FisReasonCodeMapper.toIso20022(null)).isEqualTo("NARR");
    }

    @Test
    void isAccepted_accepted() {
        assertThat(FisReasonCodeMapper.isAccepted("ACCEPTED")).isTrue();
        // FIS also accepts the legacy "APPROVED" status from older IBS versions
        assertThat(FisReasonCodeMapper.isAccepted("APPROVED")).isTrue();
    }

    @Test
    void isAccepted_rejectedAndPending_returnFalse() {
        assertThat(FisReasonCodeMapper.isAccepted("REJECTED")).isFalse();
        assertThat(FisReasonCodeMapper.isAccepted("PENDING")).isFalse();
        assertThat(FisReasonCodeMapper.isAccepted(null)).isFalse();
    }
}
