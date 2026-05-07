package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.acl.core.CoreBankingResponse.Status;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Layer 2 — Sandbox Core Banking Adapter
 *
 * <p>A deterministic, in-process simulation of a core banking system.
 * Enables full end-to-end development and testing of every other layer
 * (Gateway, SyncAsyncBridge, SagaOrchestrator, ShadowLedger) without
 * requiring access to a Fiserv, FIS, or Jack Henry vendor sandbox.
 *
 * <h2>Activation</h2>
 * <p>Set the environment variable {@code CORE_ADAPTER=sandbox} or configure
 * {@code openfednow.adapter=sandbox} in {@code application.yml}.
 * This is the default profile for local development.
 *
 * <h2>Scenario Routing</h2>
 * <p>Responses are driven by the {@code creditorAccountNumber} field of the
 * inbound pacs.008 message. Use the following prefix conventions to trigger
 * specific scenarios:
 *
 * <table>
 *   <tr><th>Account prefix</th><th>Response</th><th>ISO 20022 reason</th></tr>
 *   <tr><td>{@code RJCT_FUNDS_}</td><td>REJECTED</td><td>AM04 — insufficient funds</td></tr>
 *   <tr><td>{@code RJCT_ACCT_}</td><td>REJECTED</td><td>AC01 — invalid account number</td></tr>
 *   <tr><td>{@code RJCT_CLOSED_}</td><td>REJECTED</td><td>AC04 — closed account</td></tr>
 *   <tr><td>{@code TOUT_}</td><td>TIMEOUT</td><td>n/a — triggers SyncAsyncBridge path</td></tr>
 *   <tr><td>{@code PEND_}</td><td>PENDING</td><td>n/a — async core response</td></tr>
 *   <tr><td>(any other value)</td><td>ACCEPTED</td><td>n/a</td></tr>
 * </table>
 *
 * <p>For balance lookups, accounts prefixed with {@code LOWBAL_} return
 * {@code $1.00}; all others return {@code openfednow.sandbox.default-balance}.
 *
 * <h2>Configuration</h2>
 * <pre>
 * openfednow:
 *   sandbox:
 *     core-available: true          # toggle isCoreSystemAvailable()
 *     default-balance: 50000.00     # balance returned for normal accounts
 *     simulate-latency-ms: 0        # artificial delay per call (ms)
 * </pre>
 *
 * @see CoreBankingAdapter
 */
@Component("sandboxAdapter")
public class SandboxAdapter implements CoreBankingAdapter {

    // --- Account-number prefix constants for scenario routing ---

    /** Triggers REJECTED response with AM04 (insufficient funds). */
    public static final String PREFIX_REJECT_FUNDS  = "RJCT_FUNDS_";

    /** Triggers REJECTED response with AC01 (invalid account number). */
    public static final String PREFIX_REJECT_ACCOUNT = "RJCT_ACCT_";

    /** Triggers REJECTED response with AC04 (closed account). */
    public static final String PREFIX_REJECT_CLOSED  = "RJCT_CLOSED_";

    /** Triggers a TIMEOUT response, exercising the SyncAsyncBridge fallback path. */
    public static final String PREFIX_TIMEOUT        = "TOUT_";

    /** Triggers a PENDING response, simulating an async core acknowledgement. */
    public static final String PREFIX_PENDING        = "PEND_";

    /** Triggers a low ($1.00) balance result from {@link #getAvailableBalance}. */
    public static final String PREFIX_LOW_BALANCE    = "LOWBAL_";

    // --- Configurable fields (injected by Spring; settable via constructor for tests) ---

    @Value("${openfednow.sandbox.core-available:true}")
    private boolean coreAvailable;

    @Value("${openfednow.sandbox.default-balance:50000.00}")
    private BigDecimal defaultBalance;

    @Value("${openfednow.sandbox.simulate-latency-ms:0}")
    private long simulateLatencyMs;

    /** Default constructor — Spring populates fields via {@code @Value}. */
    public SandboxAdapter() {}

    /**
     * Constructor for unit tests: injects configuration directly without a
     * Spring context.
     *
     * @param coreAvailable      value returned by {@link #isCoreSystemAvailable()}
     * @param defaultBalance     balance returned for non-{@code LOWBAL_} accounts
     * @param simulateLatencyMs  artificial delay added to each call, in milliseconds
     */
    SandboxAdapter(boolean coreAvailable, BigDecimal defaultBalance, long simulateLatencyMs) {
        this.coreAvailable = coreAvailable;
        this.defaultBalance = defaultBalance;
        this.simulateLatencyMs = simulateLatencyMs;
    }

    // --- CoreBankingAdapter implementation ---

    /**
     * Routes the request to a scenario based on the {@code creditorAccountNumber}
     * prefix in the pacs.008 message.
     *
     * @param message the validated pacs.008 credit transfer
     * @return a deterministic {@link CoreBankingResponse} matching the scenario
     */
    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        simulateLatency();

        String account = message.getCreditorAccountNumber();
        String ref = "SANDBOX-" + message.getTransactionId();

        if (account != null) {
            if (account.startsWith(PREFIX_REJECT_FUNDS)) {
                return new CoreBankingResponse(Status.REJECTED, "AM04", "SANDBOX_INSUFFICIENT_FUNDS", ref);
            }
            if (account.startsWith(PREFIX_REJECT_ACCOUNT)) {
                return new CoreBankingResponse(Status.REJECTED, "AC01", "SANDBOX_INVALID_ACCOUNT", ref);
            }
            if (account.startsWith(PREFIX_REJECT_CLOSED)) {
                return new CoreBankingResponse(Status.REJECTED, "AC04", "SANDBOX_CLOSED_ACCOUNT", ref);
            }
            if (account.startsWith(PREFIX_TIMEOUT)) {
                return new CoreBankingResponse(Status.TIMEOUT, null, "SANDBOX_TIMEOUT", ref);
            }
            if (account.startsWith(PREFIX_PENDING)) {
                return new CoreBankingResponse(Status.PENDING, null, "SANDBOX_PENDING", ref);
            }
        }

        return new CoreBankingResponse(Status.ACCEPTED, null, "SANDBOX_OK", ref);
    }

    /**
     * Returns {@code $1.00} for accounts prefixed with {@code LOWBAL_},
     * otherwise returns the configured default balance.
     *
     * @param accountId the institution's internal account identifier
     * @return available balance in USD
     */
    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        if (accountId != null && accountId.startsWith(PREFIX_LOW_BALANCE)) {
            return new BigDecimal("1.00");
        }
        return defaultBalance;
    }

    /**
     * Returns the value of {@code openfednow.sandbox.core-available} (default: {@code true}).
     * Set to {@code false} in config to exercise the Shadow Ledger maintenance-window path.
     *
     * @return {@code true} if the simulated core is online
     */
    @Override
    public boolean isCoreSystemAvailable() {
        return coreAvailable;
    }

    @Override
    public String getVendorName() {
        return "Sandbox";
    }

    // --- Internal helpers ---

    private void simulateLatency() {
        if (simulateLatencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(simulateLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
