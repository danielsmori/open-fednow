package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
 * <h2>Circuit breaker</h2>
 * <p>All three methods are protected by the {@code corebanking} circuit breaker
 * (configured in {@code application.yml}).  When the circuit opens:
 * <ul>
 *   <li>{@link #postCreditTransfer} returns a {@code TIMEOUT} response so the
 *       SyncAsyncBridge can fall back to provisional acceptance.</li>
 *   <li>{@link #isCoreSystemAvailable} returns {@code false}, causing the
 *       AvailabilityBridge to activate Shadow Ledger bridge mode.</li>
 *   <li>{@link #getAvailableBalance} throws so that callers can consult
 *       the Shadow Ledger instead.</li>
 * </ul>
 *
 * @see CoreBankingAdapter
 */
@Component("fiservAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "fiserv")
public class FiservAdapter implements CoreBankingAdapter {

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackPostCreditTransfer")
    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        // TODO: implement Fiserv transaction posting
        // - translate pacs.008 to Fiserv transaction format via FiservMessageTranslator
        // - submit via Fiserv API (DNA REST or Precision/Premier SOAP)
        // - map response to CoreBankingResponse
        throw new UnsupportedOperationException("Fiserv adapter — in development");
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackGetAvailableBalance")
    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        // TODO: implement Fiserv balance inquiry
        throw new UnsupportedOperationException("Fiserv adapter — in development");
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackIsCoreSystemAvailable")
    @Override
    public boolean isCoreSystemAvailable() {
        // TODO: implement Fiserv health check endpoint call
        throw new UnsupportedOperationException("Fiserv adapter — in development");
    }

    @Override
    public String getVendorName() {
        return "Fiserv";
    }

    // ── Circuit breaker fallbacks ────────────────────────────────────────────

    private CoreBankingResponse fallbackPostCreditTransfer(Pacs008Message message, Throwable t) {
        // Return TIMEOUT so SyncAsyncBridge can issue a provisional FedNow acceptance
        // backed by Shadow Ledger validation and queue the transaction for replay.
        return new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);
    }

    private BigDecimal fallbackGetAvailableBalance(String accountId, Throwable t) {
        // The Shadow Ledger is the authoritative balance source when the core is
        // unreachable.  Signal the caller to consult it instead.
        throw new IllegalStateException(
                "Fiserv core unreachable (circuit open) — use Shadow Ledger for balance inquiry", t);
    }

    private boolean fallbackIsCoreSystemAvailable(Throwable t) {
        // An open circuit means the core is not responding — treat as unavailable
        // so that AvailabilityBridge activates Shadow Ledger bridge mode.
        return false;
    }
}
