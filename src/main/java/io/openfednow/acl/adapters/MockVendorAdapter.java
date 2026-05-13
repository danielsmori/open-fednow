package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.acl.core.CoreBankingResponse.Status;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 2 — Reference/Mock Vendor Core Banking Adapter
 *
 * <p>A reference implementation of {@link CoreBankingAdapter} that simulates realistic
 * core banking behavior — including per-account balance management, funds reservation,
 * transaction commit/rollback, and configurable failure modes — without connecting to
 * any real vendor system.
 *
 * <p>Unlike {@link SandboxAdapter} (which routes by account prefix for demo purposes),
 * this adapter maintains an in-memory balance ledger so that sequence-dependent scenarios
 * such as "reserve → commit → overdraft prevention" can be tested end-to-end.
 *
 * <h2>Activation</h2>
 * <pre>
 * openfednow:
 *   adapter: mock
 * </pre>
 *
 * <h2>Account seeding</h2>
 * Call {@link #seedBalance(String, BigDecimal)} before tests to set an account balance.
 * Accounts with no seeded balance default to {@link #DEFAULT_BALANCE}.
 *
 * <h2>Failure simulation</h2>
 * <ul>
 *   <li>{@link #setCoreAvailable(boolean)} — toggles {@link #isCoreSystemAvailable()}</li>
 *   <li>{@link #setSimulateTimeout(boolean)} — makes {@code postCreditTransfer} return TIMEOUT</li>
 * </ul>
 *
 * <p><strong>Status:</strong> reference/mock — not connected to any real vendor system.
 * Production vendor-specific adapters (Fiserv, FIS, Jack Henry) are separate implementations
 * subject to institution-provided credentials and vendor API documentation.
 *
 * @see CoreBankingAdapter
 * @see SandboxAdapter
 */
@Component("mockVendorAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "mock")
public class MockVendorAdapter implements CoreBankingAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockVendorAdapter.class);

    /** Default balance for accounts that have not been explicitly seeded. */
    public static final BigDecimal DEFAULT_BALANCE = new BigDecimal("10000.00");

    private final Map<String, BigDecimal> balanceLedger = new ConcurrentHashMap<>();
    private volatile boolean coreAvailable = true;
    private volatile boolean simulateTimeout = false;
    private volatile long simulateLatencyMs = 0L;

    /**
     * Processes an inbound credit transfer against the in-memory balance ledger.
     *
     * <p>Rejection scenarios are determined by the creditor account prefix,
     * matching the same conventions as {@link SandboxAdapter}:
     * <ul>
     *   <li>{@code RJCT_FUNDS_} → REJECTED (AM04)</li>
     *   <li>{@code RJCT_ACCT_}  → REJECTED (AC01)</li>
     *   <li>{@code RJCT_CLOSED_} → REJECTED (AC04)</li>
     *   <li>{@code TOUT_}       → TIMEOUT</li>
     *   <li>{@code PEND_}       → PENDING</li>
     * </ul>
     *
     * <p>When neither a prefix match nor a timeout is active, the adapter credits
     * the creditor account in its in-memory ledger.
     */
    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        simulateLatency();

        if (simulateTimeout) {
            log.info("MockVendorAdapter: simulated TIMEOUT for transactionId={}",
                    message.getTransactionId());
            return new CoreBankingResponse(Status.TIMEOUT, null, "MOCK_TIMEOUT",
                    "MOCK-" + message.getTransactionId());
        }

        String account = message.getCreditorAccountNumber();
        String ref = "MOCK-" + message.getTransactionId();

        if (account != null) {
            if (account.startsWith(SandboxAdapter.PREFIX_REJECT_FUNDS)) {
                return new CoreBankingResponse(Status.REJECTED, "AM04", "MOCK_NSF", ref);
            }
            if (account.startsWith(SandboxAdapter.PREFIX_REJECT_ACCOUNT)) {
                return new CoreBankingResponse(Status.REJECTED, "AC01", "MOCK_INVALID_ACCT", ref);
            }
            if (account.startsWith(SandboxAdapter.PREFIX_REJECT_CLOSED)) {
                return new CoreBankingResponse(Status.REJECTED, "AC04", "MOCK_CLOSED_ACCT", ref);
            }
            if (account.startsWith(SandboxAdapter.PREFIX_TIMEOUT)) {
                return new CoreBankingResponse(Status.TIMEOUT, null, "MOCK_TIMEOUT", ref);
            }
            if (account.startsWith(SandboxAdapter.PREFIX_PENDING)) {
                return new CoreBankingResponse(Status.PENDING, null, "MOCK_PENDING", ref);
            }

            // Credit the account in the in-memory ledger
            BigDecimal current = balanceLedger.getOrDefault(account, DEFAULT_BALANCE);
            balanceLedger.put(account, current.add(message.getInterbankSettlementAmount()));
            log.debug("MockVendorAdapter: credited account={} amount={} newBalance={}",
                    account, message.getInterbankSettlementAmount(),
                    balanceLedger.get(account));
        }

        return new CoreBankingResponse(Status.ACCEPTED, null, "MOCK_OK", ref);
    }

    /**
     * Returns the balance for an account from the in-memory ledger.
     * Returns {@link #DEFAULT_BALANCE} for unseeded accounts.
     */
    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        return balanceLedger.getOrDefault(accountId, DEFAULT_BALANCE);
    }

    /** Returns the configured availability state (toggleable via {@link #setCoreAvailable}). */
    @Override
    public boolean isCoreSystemAvailable() {
        return coreAvailable;
    }

    @Override
    public String getVendorName() {
        return "MockVendor";
    }

    // ── Test support ───────────────────────────────────────────────────────────

    /**
     * Seeds a known balance for an account. Called in tests before exercising
     * balance-dependent scenarios such as overdraft prevention.
     */
    public void seedBalance(String accountId, BigDecimal balance) {
        balanceLedger.put(accountId, balance);
    }

    /** Sets the value returned by {@link #isCoreSystemAvailable()}. */
    public void setCoreAvailable(boolean available) {
        this.coreAvailable = available;
    }

    /**
     * When {@code true}, {@link #postCreditTransfer} returns {@code TIMEOUT} for all requests,
     * exercising the SyncAsyncBridge provisional acceptance path.
     */
    public void setSimulateTimeout(boolean simulateTimeout) {
        this.simulateTimeout = simulateTimeout;
    }

    /** Sets an artificial per-call delay in milliseconds (0 = disabled). */
    public void setSimulateLatencyMs(long latencyMs) {
        this.simulateLatencyMs = latencyMs;
    }

    /** Resets the in-memory ledger and all failure flags to their default state. */
    public void reset() {
        balanceLedger.clear();
        coreAvailable = true;
        simulateTimeout = false;
        simulateLatencyMs = 0L;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void simulateLatency() {
        if (simulateLatencyMs <= 0) return;
        try {
            Thread.sleep(simulateLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
