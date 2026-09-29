package io.openfednow.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SQL-authoritative synthetic outbound slice. A committed SUBMITTING intent
 * precedes the external call; a crash at that boundary is ambiguous, never a
 * reason to send again. This does not change the legacy Redis-backed route.
 */
@Service
public class ReliablePaymentService {
    private static final Logger log = LoggerFactory.getLogger(ReliablePaymentService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ReliabilityRailPort rail;

    public ReliablePaymentService(JdbcTemplate jdbc, TransactionTemplate transaction,
                                  ObjectMapper mapper, ReliabilityRailPort rail) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.rail = rail;
    }

    public PaymentView submit(Pacs008Message message) {
        if (rail instanceof UnavailableRailPort) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Synthetic rail simulator is not configured");
        }
        long amount = exactUsdMinor(message);
        String institution = message.getDebtorAgentRoutingNumber();
        String fingerprint = fingerprint(message, amount);
        String requestJson = serialize(message);
        Claim claim = transaction.execute(status -> claim(message, institution, amount,
                fingerprint, requestJson));
        if (claim == null) {
            throw new IllegalStateException("Claim transaction returned no result");
        }
        if (!claim.created()) {
            return claim.payment();
        }
        if ("CORE_PENDING".equals(claim.payment().state())) {
            return claim.payment();
        }
        return dispatch(claim.payment().operationId(), message);
    }

    private PaymentView dispatch(String operationId, Pacs008Message message) {
        // Persist an attempt and possible-send boundary before invoking the rail.
        String attempt = UUID.randomUUID().toString();
        Integer won = transaction.execute(status -> jdbc.update("""
                UPDATE reliability_payment SET state = 'SUBMITTING', attempt_id = ?,
                    next_inquiry_at = NOW() + INTERVAL '30 seconds',
                    version = version + 1, updated_at = NOW()
                WHERE operation_id = ? AND state = 'RESERVED'
                """, attempt, operationId));
        if (won == null || won != 1) {
            return get(operationId); // another worker owns dispatch
        }
        try {
            RailObservation observation = rail.submit(message, attempt);
            if (observation == null) {
                markUnknown(operationId, "Missing rail response");
            } else {
                observe(operationId, observation);
            }
        } catch (Exception e) {
            log.warn("Synthetic payment submission unresolved operationId={} cause={}",
                    operationId, e.toString());
            markUnknown(operationId, "Synthetic transport outcome unknown");
        }
        return get(operationId);
    }

    private Claim claim(Pacs008Message message, String institution, long amount,
                        String fingerprint, String requestJson) {
        String operationId = UUID.randomUUID().toString();
        int inserted;
        try {
            inserted = jdbc.update("""
                    INSERT INTO reliability_payment
                      (operation_id, institution_id, business_key, payload_fingerprint,
                       request_json, transaction_id, message_id, account_id, amount_minor, state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED')
                    ON CONFLICT DO NOTHING
                    """, operationId, institution, message.getEndToEndId(), fingerprint,
                    requestJson, message.getTransactionId(), message.getMessageId(),
                    message.getDebtorAccountNumber(), amount);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Synthetic account is not provisioned", e);
        }
        if (inserted == 0) {
            List<String> identities = jdbc.queryForList("""
                    SELECT operation_id FROM reliability_payment
                    WHERE institution_id = ? AND direction = 'OUTBOUND'
                      AND rail = 'FEDNOW' AND business_key = ?
                    """, String.class, institution, message.getEndToEndId());
            if (identities.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Transaction or message identifier already belongs to another payment");
            }
            String existing = identities.get(0);
            String existingFingerprint = jdbc.queryForObject(
                    "SELECT payload_fingerprint FROM reliability_payment WHERE operation_id = ?",
                    String.class, existing);
            if (!fingerprint.equals(existingFingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Business key already belongs to a different payment payload");
            }
            return new Claim(get(existing), false);
        }

        int held = jdbc.update("""
                UPDATE reliability_account
                SET held_minor = held_minor + ?, version = version + 1
                WHERE account_id = ? AND currency = 'USD' AND exclusive_control = TRUE
                  AND ledger_minor - held_minor >= ?
                """, amount, message.getDebtorAccountNumber(), amount);
        if (held != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No enforceable USD reservation or insufficient available funds");
        }
        jdbc.update("""
                INSERT INTO reliability_effect (operation_id, effect_type, account_id, amount_minor)
                VALUES (?, 'HOLD', ?, ?)
                """, operationId, message.getDebtorAccountNumber(), amount);
        String mode = jdbc.queryForObject(
                "SELECT core_mode FROM reliability_account WHERE account_id = ?",
                String.class, message.getDebtorAccountNumber());
        if ("ASYNC".equals(mode)) {
            jdbc.update("INSERT INTO reliability_core_ack (operation_id, status) VALUES (?, 'PENDING')",
                    operationId);
            jdbc.update("""
                    UPDATE reliability_payment SET state = 'CORE_PENDING', version = version + 1,
                        updated_at = NOW() WHERE operation_id = ?
                    """, operationId);
        }
        return new Claim(get(operationId), true);
    }

    /** Synthetic asynchronous core acknowledgement and lookup, before rail dispatch. */
    public PaymentView acknowledgeSyntheticCore(String operationId) {
        transaction.executeWithoutResult(status -> {
            PaymentView current = lock(operationId);
            if (!"CORE_PENDING".equals(current.state())) return;
            jdbc.update("""
                    UPDATE reliability_core_ack SET status = 'ACKED', acknowledged_at = NOW()
                    WHERE operation_id = ? AND status = 'PENDING'
                    """, operationId);
            jdbc.update("""
                    UPDATE reliability_payment SET state = 'RESERVED', version = version + 1,
                        updated_at = NOW() WHERE operation_id = ? AND state = 'CORE_PENDING'
                    """, operationId);
        });
        PaymentView current = get(operationId);
        if (!"RESERVED".equals(current.state())) return current;
        String payload = jdbc.queryForObject(
                "SELECT request_json FROM reliability_payment WHERE operation_id = ?",
                String.class, operationId);
        try {
            return dispatch(operationId, mapper.readValue(payload, Pacs008Message.class));
        } catch (JsonProcessingException e) {
            markUnknown(operationId, "Persisted request unreadable; investigate before sending");
            return get(operationId);
        }
    }

    /** Replays only pre-send RESERVED work; SUBMITTING is never resubmitted. */
    public int dispatchRecoverable(int limit) {
        if (rail instanceof UnavailableRailPort) return 0;
        List<String> ids = jdbc.queryForList("""
                SELECT operation_id FROM reliability_payment WHERE state = 'RESERVED'
                ORDER BY created_at LIMIT ?
                """, String.class, limit);
        int dispatched = 0;
        for (String id : ids) {
            String payload = jdbc.queryForObject(
                    "SELECT request_json FROM reliability_payment WHERE operation_id = ?",
                    String.class, id);
            try {
                dispatch(id, mapper.readValue(payload, Pacs008Message.class));
                dispatched++;
            } catch (JsonProcessingException e) {
                log.error("Persisted synthetic request unreadable operationId={}", id);
                markUnknown(id, "Persisted request unreadable; investigate before sending");
            }
        }
        return dispatched;
    }

    public PaymentView get(String operationId) {
        List<PaymentView> rows = jdbc.query("""
                SELECT * FROM reliability_payment WHERE operation_id = ?
                """, this::map, operationId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        return rows.get(0);
    }

    public PaymentView getByBusinessKey(String institutionId, String businessKey) {
        List<PaymentView> rows = jdbc.query("""
                SELECT * FROM reliability_payment
                WHERE institution_id = ? AND direction = 'OUTBOUND'
                  AND rail = 'FEDNOW' AND business_key = ?
                """, this::map, institutionId, businessKey);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        return rows.get(0);
    }

    public List<PaymentView> unresolved(int limit) {
        return jdbc.query("""
                SELECT * FROM reliability_payment
                WHERE state IN ('CORE_PENDING', 'RESERVED', 'SUBMITTING',
                                'OUTCOME_UNKNOWN', 'INVESTIGATION')
                   OR investigation_reason LIKE 'Conflicting%'
                ORDER BY created_at, operation_id LIMIT ?
                """, this::map, limit);
    }

    /** Audited synthetic review; the actor cannot enter a settlement status. */
    public PaymentView review(String operationId, String actor, String reason,
                              String evidenceReference) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()
                || evidenceReference == null || evidenceReference.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Actor, reason and evidence reference are required");
        }
        PaymentView before = get(operationId);
        RailObservation observation = null;
        if (rail instanceof UnavailableRailPort) {
            log.warn("Synthetic review without rail simulator operationId={}", operationId);
        } else {
            try {
                observation = rail.inquire(before);
            } catch (Exception e) {
                log.warn("Synthetic review inquiry unavailable operationId={}", operationId);
            }
        }
        if (observation != null && observation.finalPaymentStatus()) {
            final RailObservation verified = observation;
            transaction.executeWithoutResult(status -> {
                PaymentView current = lock(operationId);
                if ("INVESTIGATION".equals(current.state())
                        && current.investigationReason() != null
                        && current.investigationReason().startsWith("Synthetic inquiry unavailable")) {
                    jdbc.update("""
                            UPDATE reliability_payment SET state = 'OUTCOME_UNKNOWN',
                                version = version + 1, updated_at = NOW()
                            WHERE operation_id = ?
                            """, operationId);
                }
            });
            observe(operationId, verified);
        }
        PaymentView after = get(operationId);
        jdbc.update("""
                INSERT INTO reliability_investigation_audit
                    (operation_id, actor, reason, prior_state, resulting_state, evidence_reference)
                VALUES (?, ?, ?, ?, ?, ?)
                """, operationId, actor, reason, before.state(), after.state(), evidenceReference);
        return after;
    }

    /** Test-fixture provisioning is exposed only by the non-production fixture API. */
    public AccountView provisionSyntheticAccount(String accountId, long ledgerMinor,
                                                  boolean exclusiveControl) {
        return provisionSyntheticAccount(accountId, ledgerMinor, exclusiveControl, "SYNC");
    }

    public AccountView provisionSyntheticAccount(String accountId, long ledgerMinor,
                                                  boolean exclusiveControl, String coreMode) {
        if (ledgerMinor < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Initial ledger balance must be nonnegative");
        }
        try {
            jdbc.update("""
                    INSERT INTO reliability_account
                        (account_id, currency, ledger_minor, held_minor, exclusive_control, core_mode)
                    VALUES (?, 'USD', ?, 0, ?, ?)
                    """, accountId, ledgerMinor, exclusiveControl, coreMode);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Synthetic account already exists or is invalid", e);
        }
        return account(accountId);
    }

    public AccountView account(String accountId) {
        List<AccountView> rows = jdbc.query("""
                SELECT account_id, ledger_minor, held_minor, exclusive_control, core_mode, version
                FROM reliability_account WHERE account_id = ?
                """, (rs, row) -> new AccountView(rs.getString("account_id"),
                rs.getLong("ledger_minor"), rs.getLong("held_minor"),
                rs.getBoolean("exclusive_control"), rs.getString("core_mode"),
                rs.getLong("version")), accountId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic account not found");
        }
        return rows.get(0);
    }

    public AccountView syntheticExternalDebit(String accountId, long amountMinor) {
        if (amountMinor <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Debit must be positive");
        }
        int changed = jdbc.update("""
                UPDATE reliability_account SET ledger_minor = ledger_minor - ?,
                    version = version + 1
                WHERE account_id = ? AND ledger_minor - held_minor >= ?
                """, amountMinor, accountId, amountMinor);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No available synthetic funds for external debit");
        }
        return account(accountId);
    }

    public List<EffectView> effects(String operationId) {
        get(operationId);
        return jdbc.query("""
                SELECT operation_id, effect_type, account_id, amount_minor
                FROM reliability_effect WHERE operation_id = ? ORDER BY applied_at, effect_type
                """, (rs, row) -> new EffectView(rs.getString("operation_id"),
                rs.getString("effect_type"), rs.getString("account_id"),
                rs.getLong("amount_minor")), operationId);
    }

    public PaymentView makeSyntheticInquiryDue(String operationId) {
        PaymentView current = get(operationId);
        if (!"SUBMITTING".equals(current.state()) && !"OUTCOME_UNKNOWN".equals(current.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only unresolved synthetic payments can be made inquiry-eligible");
        }
        jdbc.update("""
                UPDATE reliability_payment SET next_inquiry_at = NOW() - INTERVAL '1 second'
                WHERE operation_id = ?
                """, operationId);
        return get(operationId);
    }

    public record AccountView(String accountId, long ledgerMinor, long heldMinor,
                              boolean exclusiveControl, String coreMode, long version) { }
    public record EffectView(String operationId, String effectType,
                             String accountId, long amountMinor) { }

    /** Applies only correlated synthetic service status; posting is tracked separately. */
    public PaymentView observe(String operationId, RailObservation observation) {
        return Objects.requireNonNull(transaction.execute(status -> applyObservation(operationId, observation)));
    }

    /** Claim due inquiry work with a lease; a delayed worker cannot apply a result. */
    public List<InquiryLease> claimDueInquiries(int batchSize) {
        return Objects.requireNonNull(transaction.execute(status -> {
            List<String> ids = jdbc.queryForList("""
                    SELECT operation_id FROM reliability_payment
                    WHERE state IN ('SUBMITTING', 'OUTCOME_UNKNOWN')
                      AND next_inquiry_at <= NOW()
                      AND (inquiry_lease_until IS NULL OR inquiry_lease_until < NOW())
                    ORDER BY next_inquiry_at, operation_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                    """, String.class, batchSize);
            return ids.stream().map(id -> {
                String token = UUID.randomUUID().toString();
                jdbc.update("""
                        UPDATE reliability_payment SET inquiry_lease_token = ?,
                            inquiry_lease_until = NOW() + INTERVAL '15 seconds',
                            version = version + 1
                        WHERE operation_id = ?
                        """, token, id);
                return new InquiryLease(get(id), token);
            }).toList();
        }));
    }

    public PaymentView finishInquiry(InquiryLease lease, RailObservation observation) {
        return Objects.requireNonNull(transaction.execute(status -> {
            PaymentView current = lock(lease.payment().operationId());
            String active = jdbc.queryForObject(
                    "SELECT inquiry_lease_token FROM reliability_payment WHERE operation_id = ?",
                    String.class, current.operationId());
            Boolean live = jdbc.queryForObject("""
                    SELECT inquiry_lease_until > NOW() FROM reliability_payment WHERE operation_id = ?
                    """, Boolean.class, current.operationId());
            if (!lease.token().equals(active) || !Boolean.TRUE.equals(live)) {
                return current; // fenced stale worker
            }
            jdbc.update("""
                    UPDATE reliability_payment SET inquiry_lease_token = NULL,
                        inquiry_lease_until = NULL, inquiry_count = inquiry_count + 1,
                        version = version + 1 WHERE operation_id = ?
                    """, current.operationId());
            if (!"SUBMITTING".equals(current.state()) && !"OUTCOME_UNKNOWN".equals(current.state())) {
                return get(current.operationId());
            }
            if (observation == null) {
                if (current.inquiryCount() >= 2 || current.createdAt().isBefore(Instant.now().minus(Duration.ofHours(24)))) {
                    investigate(current, "Synthetic inquiry unavailable, untraceable, or expired");
                } else {
                    jdbc.update("""
                            UPDATE reliability_payment SET state = 'OUTCOME_UNKNOWN',
                                next_inquiry_at = NOW() + INTERVAL '30 seconds',
                                updated_at = NOW(), version = version + 1
                            WHERE operation_id = ?
                            """, current.operationId());
                }
                return get(current.operationId());
            }
            return applyObservation(current.operationId(), observation);
        }));
    }

    public record InquiryLease(PaymentView payment, String token) { }

    private PaymentView applyObservation(String operationId, RailObservation observation) {
        PaymentView current = lock(operationId);
        if (observation == null || !"SYNTHETIC_RAIL".equals(observation.source())
                || observation.reference() == null || observation.reference().isBlank()
                || !Objects.equals(current.transactionId(), observation.transactionId())
                || !Objects.equals(current.businessKey(), observation.businessKey())
                || !Objects.equals(current.messageId(), observation.messageId())) {
            investigate(current, "Uncorrelated or unauthenticated synthetic status");
            return get(operationId);
        }
        if (!observation.finalPaymentStatus()) {
            markUnknownInTransaction(current, "Payment status not final");
            return get(operationId);
        }
        int eventInserted = jdbc.update("""
                INSERT INTO reliability_status_event
                    (operation_id, source, reference, service_status, disposition)
                VALUES (?, ?, ?, ?, 'APPLIED') ON CONFLICT DO NOTHING
                """, operationId, observation.source(), observation.reference(), observation.status());
        if (eventInserted == 0) {
            String existingStatus = jdbc.queryForObject("""
                    SELECT service_status FROM reliability_status_event
                    WHERE operation_id = ? AND source = ? AND reference = ?
                    """, String.class, operationId, observation.source(), observation.reference());
            if (!observation.status().equals(existingStatus)) {
                investigate(current, "Conflicting status for same source reference");
            }
            return get(operationId);
        }
        if ("SETTLED".equals(current.state()) || "REJECTED".equals(current.state())) {
            if (!current.state().equals(observation.status())) {
                jdbc.update("""
                        UPDATE reliability_status_event SET disposition = 'CONFLICT'
                        WHERE operation_id = ? AND source = ? AND reference = ?
                        """, operationId, observation.source(), observation.reference());
                investigate(current, "Conflicting final synthetic status " + observation.reference());
            }
            return get(operationId);
        }
        if ("INVESTIGATION".equals(current.state())) {
            return current; // requires audited operator review
        }
        String effectType = "SETTLED".equals(observation.status()) ? "POST" : "RELEASE";
        int effectInserted = jdbc.update("""
                INSERT INTO reliability_effect (operation_id, effect_type, account_id, amount_minor)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, operationId, effectType, current.accountId(), current.amountMinor());
        if (effectInserted == 1) {
            int changed = "POST".equals(effectType)
                    ? jdbc.update("""
                        UPDATE reliability_account SET held_minor = held_minor - ?,
                            ledger_minor = ledger_minor - ?, version = version + 1
                        WHERE account_id = ? AND held_minor >= ? AND ledger_minor >= ?
                        """, current.amountMinor(), current.amountMinor(), current.accountId(),
                            current.amountMinor(), current.amountMinor())
                    : jdbc.update("""
                        UPDATE reliability_account SET held_minor = held_minor - ?, version = version + 1
                        WHERE account_id = ? AND held_minor >= ?
                        """, current.amountMinor(), current.accountId(), current.amountMinor());
            if (changed != 1) {
                throw new IllegalStateException("Financial effect/account state disagree");
            }
        }
        jdbc.update("""
                UPDATE reliability_payment SET state = ?, service_status = ?, status_source = ?,
                    status_reference = ?, next_inquiry_at = NULL, updated_at = NOW(), version = version + 1
                WHERE operation_id = ?
                """, observation.status(), observation.status(), observation.source(),
                observation.reference(), operationId);
        return get(operationId);
    }

    public PaymentView markUnknown(String operationId, String reason) {
        return Objects.requireNonNull(transaction.execute(status -> {
            PaymentView current = lock(operationId);
            markUnknownInTransaction(current, reason);
            return get(operationId);
        }));
    }

    private void markUnknownInTransaction(PaymentView current, String reason) {
        if ("SUBMITTING".equals(current.state()) || "RESERVED".equals(current.state())) {
            jdbc.update("""
                    UPDATE reliability_payment SET state = 'OUTCOME_UNKNOWN',
                        investigation_reason = ?, next_inquiry_at = NOW() + INTERVAL '30 seconds',
                        updated_at = NOW(), version = version + 1
                    WHERE operation_id = ?
                    """, reason, current.operationId());
        }
    }

    private void investigate(PaymentView current, String reason) {
        jdbc.update("""
                UPDATE reliability_payment SET state = CASE
                    WHEN state IN ('SETTLED', 'REJECTED') THEN state ELSE 'INVESTIGATION' END,
                    investigation_reason = ?, next_inquiry_at = NULL,
                    updated_at = NOW(), version = version + 1
                WHERE operation_id = ?
                """, reason, current.operationId());
    }

    /** Receiver posting is a separate synthetic observation, never rail settlement. */
    public PaymentView observeSyntheticPosting(String operationId, String postingStatus,
                                               String reference) {
        if (!"POSTED".equals(postingStatus) && !"PENDING".equals(postingStatus)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unsupported synthetic posting status");
        }
        if (reference == null || reference.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Posting reference required");
        }
        return Objects.requireNonNull(transaction.execute(status -> {
            PaymentView current = lock(operationId);
            int inserted = jdbc.update("""
                    INSERT INTO reliability_status_event
                        (operation_id, source, reference, service_status, disposition)
                    VALUES (?, 'SYNTHETIC_RECEIVER', ?, ?, 'POSTING_ONLY')
                    ON CONFLICT DO NOTHING
                    """, operationId, reference, postingStatus);
            if (inserted == 1) {
                jdbc.update("""
                        UPDATE reliability_payment SET posting_status = ?,
                            version = version + 1, updated_at = NOW()
                        WHERE operation_id = ?
                        """, postingStatus, operationId);
            }
            return get(current.operationId());
        }));
    }

    private PaymentView lock(String operationId) {
        List<PaymentView> rows = jdbc.query(
                "SELECT * FROM reliability_payment WHERE operation_id = ? FOR UPDATE",
                this::map, operationId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        return rows.get(0);
    }

    private PaymentView map(ResultSet rs, int row) throws SQLException {
        return new PaymentView(rs.getString("operation_id"), rs.getString("institution_id"),
                rs.getString("business_key"), rs.getString("transaction_id"),
                rs.getString("message_id"), rs.getString("account_id"),
                rs.getLong("amount_minor"), rs.getString("state"),
                rs.getString("service_status"), rs.getString("posting_status"),
                rs.getString("status_source"), rs.getString("status_reference"),
                rs.getString("attempt_id"), rs.getLong("version"),
                timestamp(rs, "created_at"), timestamp(rs, "updated_at"),
                timestamp(rs, "next_inquiry_at"), rs.getInt("inquiry_count"),
                rs.getString("investigation_reason"));
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static long exactUsdMinor(Pacs008Message message) {
        if (!"USD".equals(message.getInterbankSettlementCurrency())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Only USD is supported");
        }
        try {
            long cents = message.getInterbankSettlementAmount().movePointRight(2).longValueExact();
            if (cents <= 0) throw new ArithmeticException("Amount must be positive");
            return cents;
        } catch (NullPointerException | ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Amount must be a positive exact USD cent amount", e);
        }
    }

    private String fingerprint(Pacs008Message message, long amount) {
        String canonical = String.join("\u001f", "v1", message.getDebtorAgentRoutingNumber(),
                message.getEndToEndId(), message.getTransactionId(), message.getMessageId(),
                message.getDebtorAccountNumber(), message.getCreditorAgentRoutingNumber(),
                message.getCreditorAccountNumber(), message.getDebtorName(),
                message.getCreditorName(), Objects.toString(message.getRemittanceInformation(), ""),
                Long.toString(amount), "USD");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String serialize(Pacs008Message message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment payload", e);
        }
    }

    private record Claim(PaymentView payment, boolean created) { }
}
