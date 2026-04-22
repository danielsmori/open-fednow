package io.openfednow.acl.core;

import io.openfednow.iso20022.Pacs008Message;

import java.math.BigDecimal;

/**
 * Layer 2 — Core Banking Adapter (Abstract Interface)
 *
 * <p>The Anti-Corruption Layer's vendor-specific integration point.
 * Each supported core banking platform (Fiserv, FIS, Jack Henry) provides
 * a concrete implementation of this interface.
 *
 * <p>This interface defines the minimal contract required to integrate any
 * core banking system with the OpenFedNow framework. Approximately 85% of
 * the framework is shared across all implementations; only the concrete
 * adapter — this interface's implementation — varies per vendor (~15% of
 * total engineering scope).
 *
 * <p>Implementations must handle:
 * <ul>
 *   <li>Translation between ISO 20022 and the vendor's proprietary format</li>
 *   <li>The synchronous-to-asynchronous bridge (FedNow requires sub-20-second
 *       synchronous responses; legacy core processing is inherently async)</li>
 *   <li>Vendor-specific authentication and session management</li>
 *   <li>Error code mapping from vendor-specific codes to ISO 20022 reason codes</li>
 * </ul>
 *
 * @see io.openfednow.acl.adapters.FiservAdapter
 * @see io.openfednow.acl.adapters.FisAdapter
 * @see io.openfednow.acl.adapters.JackHenryAdapter
 */
public interface CoreBankingAdapter {

    /**
     * Posts an inbound credit transfer to the core banking system.
     * The adapter is responsible for translating the ISO 20022 message into
     * the vendor's proprietary transaction format and submitting it.
     *
     * @param message the validated pacs.008 credit transfer
     * @return a CoreBankingResponse indicating success or failure with
     *         vendor-specific status codes mapped to ISO 20022 reason codes
     */
    CoreBankingResponse postCreditTransfer(Pacs008Message message);

    /**
     * Retrieves the available balance for an account from the core system.
     * Used to validate outbound payment requests before submission to FedNow.
     *
     * @param accountId the institution's internal account identifier
     * @return available balance in USD
     */
    BigDecimal getAvailableBalance(String accountId);

    /**
     * Checks whether the core banking system is currently available.
     * Used by the Shadow Ledger bridge to determine whether to queue
     * transactions or submit them directly.
     *
     * @return true if the core system is online and accepting transactions
     */
    boolean isCoreSystemAvailable();

    /**
     * Returns the name of the core banking vendor this adapter implements.
     * Used for logging, metrics, and health reporting.
     *
     * @return vendor name (e.g., "Fiserv-DNA", "JackHenry-SilverLake", "FIS-Horizon")
     */
    String getVendorName();
}
