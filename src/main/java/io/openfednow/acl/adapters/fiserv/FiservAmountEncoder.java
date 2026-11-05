package io.openfednow.acl.adapters.fiserv;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Encodes and decodes monetary amounts in the legacy local fixture format.
 * This cent-string representation is not a verified Banking Hub amount contract.
 * For example:
 * <ul>
 *   <li>{@code $1,000.50} → {@code "100050"}</li>
 *   <li>{@code "75000"}   → {@code $750.00}</li>
 * </ul>
 *
 */
public class FiservAmountEncoder {

    private static final int USD_SCALE = 2;
    private static final BigDecimal CENTS = new BigDecimal("100");

    private FiservAmountEncoder() {}

    /**
     * Encodes a {@link BigDecimal} USD amount to the fixture's cent string.
     *
     * @param amount USD amount (e.g. {@code 1000.50})
     * @return fixed-point cent string (e.g. {@code "100050"})
     * @throws ArithmeticException if an encoded result cannot be represented exactly
     */
    public static String encode(BigDecimal amount) {
        return amount
                .setScale(USD_SCALE, RoundingMode.HALF_UP)
                .multiply(CENTS)
                .toBigIntegerExact()
                .toString();
    }

    /**
     * Decodes a fixture cent string to a {@link BigDecimal} USD amount.
     *
     * @param encoded fixed-point cent string (e.g. {@code "100050"})
     * @return USD amount with scale 2 (e.g. {@code 1000.50})
     */
    public static BigDecimal decode(String encoded) {
        return new BigDecimal(encoded)
                .divide(CENTS)
                .setScale(USD_SCALE, RoundingMode.UNNECESSARY);
    }
}
