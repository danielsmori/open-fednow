package io.openfednow.acl.adapters;

import io.openfednow.acl.adapters.fiserv.FiservAmountEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FiservAmountEncoder}.
 *
 * Verifies the fixed-point cent encoding used by Fiserv Communicator Open:
 * amounts are transmitted as integer strings in minor currency units (cents).
 */
class FiservAmountEncoderTest {

    @ParameterizedTest(name = "${0} encodes to \"{1}\"")
    @CsvSource({
            "1000.50,  100050",
            "1000.00,  100000",
            "0.01,         1",
            "750.00,   75000",
            "25000.00, 2500000",
            "0.10,        10",
            "99999.99, 9999999"
    })
    void encode_convertsDecimalToCentString(String input, String expected) {
        assertThat(FiservAmountEncoder.encode(new BigDecimal(input.trim())))
                .isEqualTo(expected.trim());
    }

    @ParameterizedTest(name = "\"{0}\" decodes to {1}")
    @CsvSource({
            "100050,   1000.50",
            "100000,   1000.00",
            "1,           0.01",
            "75000,      750.00",
            "2500000,  25000.00",
            "10,           0.10"
    })
    void decode_convertsCentStringToDecimal(String input, String expected) {
        assertThat(FiservAmountEncoder.decode(input.trim()))
                .isEqualByComparingTo(new BigDecimal(expected.trim()));
    }

    @Test
    void roundTrip_preservesValue() {
        BigDecimal original = new BigDecimal("12345.67");
        assertThat(FiservAmountEncoder.decode(FiservAmountEncoder.encode(original)))
                .isEqualByComparingTo(original);
    }
}
