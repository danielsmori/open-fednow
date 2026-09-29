package io.openfednow.reliability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Test-only observable fixture controls, absent unless explicitly enabled. */
@RestController
@Profile("!prod")
@ConditionalOnProperty(name = "openfednow.reliability.fixture-api-enabled", havingValue = "true")
@RequestMapping("/reference/v1/fixtures")
public class ReliabilityFixtureController {
    private final ReliablePaymentService payments;

    public ReliabilityFixtureController(ReliablePaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/accounts")
    public ResponseEntity<ReliablePaymentService.AccountView> account(@RequestBody SeedAccount body) {
        return ResponseEntity.ok(payments.provisionSyntheticAccount(
                body.accountId(), body.ledgerMinor(), body.exclusiveControl(),
                body.coreMode() == null ? "SYNC" : body.coreMode()));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<ReliablePaymentService.AccountView> account(@PathVariable String accountId) {
        return ResponseEntity.ok(payments.account(accountId));
    }

    @PostMapping("/accounts/{accountId}/external-debits")
    public ResponseEntity<ReliablePaymentService.AccountView> externalDebit(
            @PathVariable String accountId, @RequestBody ExternalDebit body) {
        return ResponseEntity.ok(payments.syntheticExternalDebit(accountId, body.amountMinor()));
    }

    @PostMapping("/accounts/{accountId}/availability")
    public ResponseEntity<ReliablePaymentService.AccountView> availability(
            @PathVariable String accountId, @RequestBody CoreAvailability body) {
        return ResponseEntity.ok(payments.setSyntheticCoreAvailability(accountId, body.available()));
    }

    @GetMapping("/payments/{operationId}/effects")
    public ResponseEntity<List<ReliablePaymentService.EffectView>> effects(@PathVariable String operationId) {
        return ResponseEntity.ok(payments.effects(operationId));
    }

    @PostMapping("/payments/{operationId}/inquiry-due")
    public ResponseEntity<PaymentView> inquiryDue(@PathVariable String operationId) {
        return ResponseEntity.ok(payments.makeSyntheticInquiryDue(operationId));
    }

    @PostMapping("/payments/{operationId}/age")
    public ResponseEntity<PaymentView> age(@PathVariable String operationId) {
        return ResponseEntity.ok(payments.ageSyntheticUnresolved(operationId));
    }

    @PostMapping("/payments/{operationId}/claim-inquiry")
    public ResponseEntity<ReliablePaymentService.InquiryLease> claimInquiry(
            @PathVariable String operationId) {
        ReliablePaymentService.InquiryLease lease = payments.claimSyntheticInquiry(operationId);
        return lease == null ? ResponseEntity.status(409).build() : ResponseEntity.ok(lease);
    }

    @PostMapping("/payments/{operationId}/finish-inquiry")
    public ResponseEntity<PaymentView> finishInquiry(@PathVariable String operationId,
                                                     @RequestBody FinishInquiry body) {
        return ResponseEntity.ok(payments.finishInquiry(
                new ReliablePaymentService.InquiryLease(payments.get(operationId), body.token()),
                body.observation()));
    }

    @PostMapping("/payments/{operationId}/core-ack")
    public ResponseEntity<PaymentView> coreAck(@PathVariable String operationId) {
        return ResponseEntity.ok(payments.acknowledgeSyntheticCore(operationId));
    }

    @PostMapping("/payments/{operationId}/posting")
    public ResponseEntity<PaymentView> posting(@PathVariable String operationId,
                                                @RequestBody PostingObservation body) {
        return ResponseEntity.ok(payments.observeSyntheticPosting(
                operationId, body.status(), body.reference()));
    }

    public record SeedAccount(String accountId, long ledgerMinor,
                              boolean exclusiveControl, String coreMode) { }
    public record ExternalDebit(long amountMinor) { }
    public record CoreAvailability(boolean available) { }
    public record PostingObservation(String status, String reference) { }
    public record FinishInquiry(String token, RailObservation observation) { }
}
