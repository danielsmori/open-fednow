package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs004Message;
import io.openfednow.processing.cancellation.CancellationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code POST /fednow/return} endpoint and its wiring to
 * {@link FedNowClient#submitReturn(Pacs004Message)} — audit item #43.
 *
 * <p>Same MockMvc pattern used elsewhere in the gateway tests. The client is
 * mocked; the sandbox / HTTP implementations are exercised by their own tests.
 */
class FedNowReturnEndpointTest {

    private MockMvc mockMvc;
    private FedNowClient fedNowClient;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        fedNowClient = mock(FedNowClient.class);
        FedNowGateway gateway = new FedNowGateway(
                mock(MessageRouter.class),
                mock(CertificateManager.class),
                mock(CancellationService.class),
                fedNowClient);
        mockMvc = MockMvcBuilders
                .standaloneSetup(gateway)
                .setControllerAdvice(new ValidationErrorHandler())
                .build();
    }

    @Test
    void validReturnMessageIsForwardedToClient() throws Exception {
        Pacs002Message ack = Pacs002Message.builder()
                .messageId("FEDNOW-RET-ACK-001")
                .originalEndToEndId("E2E-ORIG-1")
                .originalTransactionId("TXN-ORIG-1")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.now())
                .build();
        when(fedNowClient.submitReturn(any(Pacs004Message.class))).thenReturn(ack);

        mockMvc.perform(post("/fednow/return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validReturn())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("ACSC"))
                .andExpect(jsonPath("$.originalEndToEndId").value("E2E-ORIG-1"));

        verify(fedNowClient, times(1)).submitReturn(any(Pacs004Message.class));
    }

    @Test
    void rejectionResponseFromFedNowIsPassedThrough() throws Exception {
        Pacs002Message rjct = Pacs002Message.rejected(
                "E2E-ORIG-2", "TXN-ORIG-2", "NOOR", "Original transaction not found at FedNow");
        when(fedNowClient.submitReturn(any(Pacs004Message.class))).thenReturn(rjct);

        mockMvc.perform(post("/fednow/return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validReturn())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("RJCT"))
                .andExpect(jsonPath("$.rejectReasonCode").value("NOOR"));
    }

    @Test
    void malformedReturnMessageIsRejectedWith400() throws Exception {
        // Missing required fields (returnId, originalMessageId, etc.)
        String malformed = "{\"messageId\":\"MSG-1\",\"returnedAmountCurrency\":\"USD\"}";

        mockMvc.perform(post("/fednow/return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformed))
                .andExpect(status().isBadRequest());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Pacs004Message validReturn() {
        return Pacs004Message.builder()
                .messageId("MSG-RET-1")
                .creationDateTime(OffsetDateTime.now())
                .returnId("RET-1")
                .originalMessageId("MSG-ORIG-1")
                .originalEndToEndId("E2E-ORIG-1")
                .originalTransactionId("TXN-ORIG-1")
                .returnedAmount(new BigDecimal("100.00"))
                .returnedAmountCurrency("USD")
                .returnReasonCode("AC04")
                .returningAgentRoutingNumber("021000021")
                .receivingAgentRoutingNumber("021000089")
                .build();
    }
}
