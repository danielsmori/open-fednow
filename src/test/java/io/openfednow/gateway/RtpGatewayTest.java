package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static io.openfednow.iso20022.Pacs002Message.TransactionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RtpGateway}.
 *
 * <p>The RTP gateway is a documented stub: TCH certificate validation is not yet
 * implemented, but the routing contract — that inbound messages are forwarded to
 * {@link MessageRouter} and the response is returned unmodified — is fully testable
 * and must hold.
 *
 * <p>No Spring context is loaded. {@link MessageRouter} and
 * {@link CertificateManager} are mocked directly; {@link RtpXmlParser} is used
 * as a real instance (pure XML parsing, no external dependencies).
 *
 * <p>The JSON content-type path is exercised here; the XML parsing path is covered
 * by {@link RtpXmlParserTest}.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>/rtp/health — returns 200 with the stub status message</li>
 *   <li>/rtp/receive (JSON) — routes through {@link MessageRouter#routeInbound} for ACSC</li>
 *   <li>/rtp/receive (JSON) — propagates RJCT response unmodified</li>
 *   <li>/rtp/receive (JSON) — does not invoke {@link CertificateManager}</li>
 * </ul>
 */
class RtpGatewayTest {

    private MessageRouter messageRouter;
    private CertificateManager certificateManager;
    private RtpGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        messageRouter = mock(MessageRouter.class);
        certificateManager = mock(CertificateManager.class);
        gateway = new RtpGateway(messageRouter, certificateManager, new RtpXmlParser());
    }

    // --- health ---

    @Test
    void healthReturns200() {
        ResponseEntity<String> response = gateway.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthBodyIdentifiesGatewayAsRtpStub() {
        ResponseEntity<String> response = gateway.health();

        assertThat(response.getBody())
                .as("Health response should identify the gateway and its stub status")
                .contains("RTP Gateway")
                .contains("stub");
    }

    // --- receiveTransfer delegates to MessageRouter ---

    @Test
    void receiveTransfer_routesInboundToMessageRouter() throws Exception {
        Pacs002Message acsc = Pacs002Message.accepted("E2E-RTP-001", "TXN-RTP-001");
        when(messageRouter.routeInbound(any())).thenReturn(ResponseEntity.ok(acsc));

        ResponseEntity<Pacs002Message> actual = gateway.receiveTransfer(
                toJson(buildMessage("E2E-RTP-001", "TXN-RTP-001", "250.00")),
                MediaType.APPLICATION_JSON_VALUE);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isNotNull();
        assertThat(actual.getBody().getTransactionStatus()).isEqualTo(ACSC);
        assertThat(actual.getBody().getOriginalEndToEndId()).isEqualTo("E2E-RTP-001");
        verify(messageRouter).routeInbound(any());
    }

    @Test
    void receiveTransfer_returnsMessageRouterResponseUnmodified() throws Exception {
        // Layer 1 must not transform the pacs.002 produced by Layers 2–4;
        // the same contract holds for both FedNow and RTP gateways.
        Pacs002Message rjct = Pacs002Message.rejected(
                "E2E-RTP-002", "TXN-RTP-002", "AM04", "Insufficient funds");
        when(messageRouter.routeInbound(any())).thenReturn(ResponseEntity.ok(rjct));

        ResponseEntity<Pacs002Message> actual = gateway.receiveTransfer(
                toJson(buildMessage("E2E-RTP-002", "TXN-RTP-002", "500.00")),
                MediaType.APPLICATION_JSON_VALUE);

        assertThat(actual.getBody().getTransactionStatus()).isEqualTo(RJCT);
        assertThat(actual.getBody().getRejectReasonCode()).isEqualTo("AM04");
        assertThat(actual.getBody().getOriginalEndToEndId()).isEqualTo("E2E-RTP-002");
    }

    @Test
    void receiveTransfer_doesNotInvokeCertificateManager() throws Exception {
        // TCH certificate validation is a TODO stub — the gateway must not call
        // the FedNow PKI validation path in place of TCH validation.
        when(messageRouter.routeInbound(any())).thenReturn(
                ResponseEntity.ok(Pacs002Message.accepted("E2E-RTP-003", "TXN-RTP-003")));

        gateway.receiveTransfer(
                toJson(buildMessage("E2E-RTP-003", "TXN-RTP-003", "100.00")),
                MediaType.APPLICATION_JSON_VALUE);

        verifyNoInteractions(certificateManager);
    }

    // --- helpers ---

    private String toJson(Pacs008Message message) throws Exception {
        return mapper.writeValueAsString(message);
    }

    private Pacs008Message buildMessage(String endToEndId, String transactionId, String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + endToEndId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorAccountNumber("ACC-RTP-99999")
                .creditorAccountNumber("ACC-RTP-12345")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .build();
    }
}
