package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Sandbox implementation of {@link FedNowClient} for local development and testing.
 *
 * <p>Returns deterministic responses based on the {@code creditorAccountNumber}
 * prefix in the outbound pacs.008, mirroring the scenario routing used by
 * {@link io.openfednow.acl.adapters.SandboxAdapter} for core banking responses.
 *
 * <p>This bean is activated only when no other {@link FedNowClient} bean is present
 * (i.e., when {@code openfednow.gateway.fednow-endpoint} is not configured). The
 * production {@link HttpFedNowClient} is created by {@link FedNowClientConfig} and
 * takes precedence whenever the endpoint property is set.
 *
 * <h2>Scenario routing</h2>
 * <table>
 *   <tr><th>Creditor account prefix</th><th>Simulated FedNow response</th></tr>
 *   <tr><td>{@code RJCT_FUNDS_}</td><td>RJCT — AM04 insufficient funds</td></tr>
 *   <tr><td>{@code RJCT_ACCT_}</td><td>RJCT — AC01 invalid account number</td></tr>
 *   <tr><td>{@code RJCT_CLOSED_}</td><td>RJCT — AC04 closed account</td></tr>
 *   <tr><td>{@code ACSP_}</td><td>ACSP — provisional acceptance (settlement in process)</td></tr>
 *   <tr><td>(any other)</td><td>ACSC — accepted and settled</td></tr>
 * </table>
 */
@Component
@ConditionalOnMissingBean(name = "httpFedNowClient")
public class SandboxFedNowClient implements FedNowClient {

    private static final Logger log = LoggerFactory.getLogger(SandboxFedNowClient.class);

    public static final String PREFIX_RJCT_FUNDS  = "RJCT_FUNDS_";
    public static final String PREFIX_RJCT_ACCT   = "RJCT_ACCT_";
    public static final String PREFIX_RJCT_CLOSED = "RJCT_CLOSED_";
    public static final String PREFIX_ACSP        = "ACSP_";

    @Override
    public Pacs002Message submitCreditTransfer(Pacs008Message message) {
        String account = message.getCreditorAccountNumber();
        log.info("SandboxFedNowClient: outbound transfer e2e={} creditorAccount={}",
                message.getEndToEndId(), account);

        if (account != null) {
            if (account.startsWith(PREFIX_RJCT_FUNDS)) {
                return rejected(message, "AM04", "Sandbox: insufficient funds at creditor institution");
            }
            if (account.startsWith(PREFIX_RJCT_ACCT)) {
                return rejected(message, "AC01", "Sandbox: invalid creditor account number");
            }
            if (account.startsWith(PREFIX_RJCT_CLOSED)) {
                return rejected(message, "AC04", "Sandbox: creditor account is closed");
            }
            if (account.startsWith(PREFIX_ACSP)) {
                return provisional(message);
            }
        }

        return accepted(message);
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private static Pacs002Message accepted(Pacs008Message message) {
        return Pacs002Message.builder()
                .messageId("FEDNOW-SANDBOX-" + message.getTransactionId())
                .originalEndToEndId(message.getEndToEndId())
                .originalTransactionId(message.getTransactionId())
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    private static Pacs002Message provisional(Pacs008Message message) {
        return Pacs002Message.builder()
                .messageId("FEDNOW-SANDBOX-" + message.getTransactionId())
                .originalEndToEndId(message.getEndToEndId())
                .originalTransactionId(message.getTransactionId())
                .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    private static Pacs002Message rejected(Pacs008Message message, String reasonCode, String description) {
        return Pacs002Message.builder()
                .messageId("FEDNOW-SANDBOX-" + message.getTransactionId())
                .originalEndToEndId(message.getEndToEndId())
                .originalTransactionId(message.getTransactionId())
                .transactionStatus(Pacs002Message.TransactionStatus.RJCT)
                .rejectReasonCode(reasonCode)
                .rejectReasonDescription(description)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }
}
