package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
 * <p>All three methods are protected by the {@code corebanking} circuit breaker.
 * See {@link FiservAdapter} for fallback semantics.
 *
 * @see CoreBankingAdapter
 */
@Component("fisAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "fis")
public class FisAdapter implements CoreBankingAdapter {

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackPostCreditTransfer")
    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        // TODO: implement FIS transaction posting
        throw new UnsupportedOperationException("FIS adapter — planned (Phase 2)");
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackGetAvailableBalance")
    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        // TODO: implement FIS balance inquiry
        throw new UnsupportedOperationException("FIS adapter — planned (Phase 2)");
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackIsCoreSystemAvailable")
    @Override
    public boolean isCoreSystemAvailable() {
        // TODO: implement FIS health check
        throw new UnsupportedOperationException("FIS adapter — planned (Phase 2)");
    }

    @Override
    public String getVendorName() {
        return "FIS";
    }

    // ── Circuit breaker fallbacks ────────────────────────────────────────────

    private CoreBankingResponse fallbackPostCreditTransfer(Pacs008Message message, Throwable t) {
        return new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);
    }

    private BigDecimal fallbackGetAvailableBalance(String accountId, Throwable t) {
        throw new IllegalStateException(
                "FIS core unreachable (circuit open) — use Shadow Ledger for balance inquiry", t);
    }

    private boolean fallbackIsCoreSystemAvailable(Throwable t) {
        return false;
    }
}
