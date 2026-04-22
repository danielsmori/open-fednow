package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Layer 2 — Jack Henry Core Banking Adapter
 *
 * <p>Implements the CoreBankingAdapter interface for Jack Henry core banking
 * platforms, including SilverLake, Symitar, and CIF 20/20.
 *
 * <p>Jack Henry platforms serve approximately 21% of U.S. banks and 12% of
 * U.S. credit unions (Phase 3 of the OpenFedNow roadmap).
 *
 * <p><strong>Status: Planned (Phase 3)</strong>
 *
 * @see CoreBankingAdapter
 */
@Component("jackHenryAdapter")
public class JackHenryAdapter implements CoreBankingAdapter {

    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        // TODO: implement Jack Henry transaction posting
        throw new UnsupportedOperationException("Jack Henry adapter — planned (Phase 3)");
    }

    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        // TODO: implement Jack Henry balance inquiry
        throw new UnsupportedOperationException("Jack Henry adapter — planned (Phase 3)");
    }

    @Override
    public boolean isCoreSystemAvailable() {
        // TODO: implement Jack Henry health check
        throw new UnsupportedOperationException("Jack Henry adapter — planned (Phase 3)");
    }

    @Override
    public String getVendorName() {
        return "Jack Henry";
    }
}
