package io.openfednow.reliability;

import io.openfednow.iso20022.Pacs008Message;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.security.Principal;
import java.util.List;

/** Versioned synthetic reference API; this is not a FedNow participant endpoint. */
@RestController
@RequestMapping("/reference/v1/payments")
public class ReliabilityPaymentController {
    private final ReliablePaymentService payments;

    public ReliabilityPaymentController(ReliablePaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<PaymentView> submit(@Valid @RequestBody Pacs008Message request) {
        PaymentView view = payments.submit(request);
        return ResponseEntity.status(pending(view) ? 202 : 200).body(view);
    }

    @GetMapping("/{operationId}")
    public ResponseEntity<PaymentView> get(@PathVariable String operationId) {
        return ResponseEntity.ok(payments.get(operationId));
    }

    @GetMapping("/by-key/{institutionId}/{businessKey}")
    public ResponseEntity<PaymentView> byKey(@PathVariable String institutionId,
                                              @PathVariable String businessKey) {
        return ResponseEntity.ok(payments.getByBusinessKey(institutionId, businessKey));
    }

    @GetMapping("/unresolved")
    public ResponseEntity<List<PaymentView>> unresolved() {
        return ResponseEntity.ok(payments.unresolved(100));
    }

    @PostMapping("/{operationId}/review")
    public ResponseEntity<PaymentView> review(@PathVariable String operationId,
                                               Principal principal,
                                               @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(payments.review(operationId, principal.getName(),
                request.reason(), request.evidenceReference()));
    }

    public record ReviewRequest(String reason, String evidenceReference) { }

    private static boolean pending(PaymentView view) {
        return !"SETTLED".equals(view.state()) && !"REJECTED".equals(view.state());
    }
}
