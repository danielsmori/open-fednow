package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Layer 2 — Fiserv Core Banking Adapter
 *
 * <p>Implements the CoreBankingAdapter interface for Fiserv core banking
 * platforms, including DNA, Precision, Premier, and Cleartouch.
 *
 * <p>Fiserv platforms serve approximately 42% of U.S. banks and 31% of
 * U.S. credit unions, making this the highest-priority adapter in the
 * OpenFedNow framework (Phase 1).
 *
 * <p><strong>Status: In Development (Phase 1)</strong>
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Fiserv DNA uses a RESTful API; Precision and Premier use SOAP/XML</li>
 *   <li>Amount fields are represented as fixed-point strings in Fiserv formats</li>
 *   <li>Character encoding is ASCII (not UTF-8) for Precision/Premier</li>
 *   <li>Session tokens expire after 30 minutes of inactivity</li>
 *   <li>Fiserv-specific rejection codes are documented in docs/anti-corruption-layer.md</li>
 * </ul>
 *
 * @see CoreBankingAdapter
 */
@Component("fiservAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "fiserv")
public class FiservAdapter implements CoreBankingAdapter {

    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        // TODO: implement Fiserv transaction posting
        // - translate pacs.008 to Fiserv transaction format via FiservMessageTranslator
        // - submit via Fiserv API (DNA REST or Precision/Premier SOAP)
        // - map response to CoreBankingResponse
        throw new UnsupportedOperationException("Fiserv adapter — in development");
    }

    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        // TODO: implement Fiserv balance inquiry
        throw new UnsupportedOperationException("Fiserv adapter — in development");
    }

    @Override
    public boolean isCoreSystemAvailable() {
        // TODO: implement Fiserv health check endpoint call
        throw new UnsupportedOperationException("Fiserv adapter — in development");
    }

    @Override
    public String getVendorName() {
        return "Fiserv";
    }
}
