package io.openfednow.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded synthetic inquiry worker; the rail profile is intentionally not a live pacs.028 mapping. */
@Component
public class ReliabilityInquiryWorker {
    private static final Logger log = LoggerFactory.getLogger(ReliabilityInquiryWorker.class);
    private final ReliablePaymentService payments;
    private final ReliabilityRailPort rail;

    public ReliabilityInquiryWorker(ReliablePaymentService payments, ReliabilityRailPort rail) {
        this.payments = payments;
        this.rail = rail;
    }

    @Scheduled(fixedDelayString = "${openfednow.reliability.inquiry-poll-millis:5000}")
    public void inquireDue() {
        if (rail instanceof UnavailableRailPort) return;
        payments.dispatchRecoverable(20);
        for (ReliablePaymentService.InquiryLease lease : payments.claimDueInquiries(20)) {
            RailObservation observation = null;
            try {
                observation = rail.inquire(lease.payment());
            } catch (Exception e) {
                log.warn("Synthetic inquiry unavailable operationId={}", lease.payment().operationId());
            }
            payments.finishInquiry(lease, observation);
        }
    }
}
