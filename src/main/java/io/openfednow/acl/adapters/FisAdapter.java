package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Layer 2 — FIS Core Banking Adapter
 *
 * <p>Implements the CoreBankingAdapter interface for FIS core banking
 * platforms, including Horizon and IBS.
 *
 * <p>FIS platforms serve approximately 9% of U.S. banks (Phase 2 of the
 * OpenFedNow roadmap).
 *
 * <p><strong>Status: Planned (Phase 2)</strong>
 *
 * @see CoreBankingAdapter
 */
@Component("fisAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "fis")
public class FisAdapter implements CoreBankingAdapter {

    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        // TODO: implement FIS transaction posting
        throw new UnsupportedOperationException("FIS adapter — planned (Phase 2)");
    }

    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        // TODO: implement FIS balance inquiry
        throw new UnsupportedOperationException("FIS adapter — planned (Phase 2)");
    }

    @Override
    public boolean isCoreSystemAvailable() {
        // TODO: implement FIS health check
        throw new UnsupportedOperationException("FIS adapter — planned (Phase 2)");
    }

    @Override
    public String getVendorName() {
        return "FIS";
    }
}
