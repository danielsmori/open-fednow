package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>All three methods are protected by the {@code corebanking} circuit breaker.
 * See {@link FiservAdapter} for fallback semantics.
 *
 * @see CoreBankingAdapter
 */
@Component("jackHenryAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "jackhenry")
public class JackHenryAdapter implements CoreBankingAdapter {

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackPostCreditTransfer")
    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        // TODO: implement Jack Henry transaction posting
        throw new UnsupportedOperationException("Jack Henry adapter — planned (Phase 3)");
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackGetAvailableBalance")
    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        // TODO: implement Jack Henry balance inquiry
        throw new UnsupportedOperationException("Jack Henry adapter — planned (Phase 3)");
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackIsCoreSystemAvailable")
    @Override
    public boolean isCoreSystemAvailable() {
        // TODO: implement Jack Henry health check
        throw new UnsupportedOperationException("Jack Henry adapter — planned (Phase 3)");
    }

    @Override
    public String getVendorName() {
        return "Jack Henry";
    }

    // ── Circuit breaker fallbacks ────────────────────────────────────────────

    private CoreBankingResponse fallbackPostCreditTransfer(Pacs008Message message, Throwable t) {
        return new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);
    }

    private BigDecimal fallbackGetAvailableBalance(String accountId, Throwable t) {
        throw new IllegalStateException(
                "Jack Henry core unreachable (circuit open) — use Shadow Ledger for balance inquiry", t);
    }

    private boolean fallbackIsCoreSystemAvailable(Throwable t) {
        return false;
    }
}
