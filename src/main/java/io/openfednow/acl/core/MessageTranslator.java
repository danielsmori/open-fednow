package io.openfednow.acl.core;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;

/**
 * Layer 2 — Message Translator
 *
 * <p>Handles bidirectional translation between the ISO 20022 message format
 * used by FedNow and the proprietary internal formats used by each core
 * banking vendor.
 *
 * <p>This is the core of the Anti-Corruption Layer pattern: by isolating all
 * translation logic here, the rest of the framework remains vendor-agnostic.
 * Each CoreBankingAdapter implementation uses a vendor-specific subclass or
 * configuration of this translator.
 *
 * <p>Translation includes:
 * <ul>
 *   <li>Field mapping (ISO 20022 field names → vendor field names)</li>
 *   <li>Character encoding (UTF-8 → vendor encoding, typically ASCII or EBCDIC)</li>
 *   <li>Amount formatting (ISO decimal → vendor fixed-point or string)</li>
 *   <li>Date/time format conversion</li>
 *   <li>Reason code mapping (vendor codes → ISO 20022 reason codes)</li>
 * </ul>
 */
public abstract class MessageTranslator {

    /**
     * Translates an ISO 20022 pacs.008 credit transfer into the vendor's
     * proprietary transaction request format.
     *
     * @param message the ISO 20022 pacs.008.001.08 message
     * @return serialized vendor-specific transaction request
     */
    public abstract String toVendorFormat(Pacs008Message message);

    /**
     * Translates a vendor response into an ISO 20022 pacs.002 payment
     * status report.
     *
     * @param vendorResponse the raw response from the core banking system
     * @param originalMessageId the end-to-end transaction ID from the original pacs.008
     * @return the ISO 20022 pacs.002.001.10 payment status report
     */
    public abstract Pacs002Message fromVendorResponse(String vendorResponse, String originalMessageId);

    /**
     * Maps a vendor-specific rejection code to the corresponding
     * ISO 20022 reason code (e.g., "INSF" for insufficient funds).
     *
     * @param vendorCode the proprietary rejection code from the core system
     * @return the ISO 20022 reason code string
     */
    public abstract String mapReasonCode(String vendorCode);
}
