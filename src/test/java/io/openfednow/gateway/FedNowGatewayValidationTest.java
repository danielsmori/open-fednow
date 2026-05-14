package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validates that {@link FedNowGateway} enforces ISO 20022 field constraints on
 * {@code @RequestBody} parameters via JSR-380 bean validation.
 *
 * <p>Uses {@link MockMvcBuilders#standaloneSetup} so the full Spring context and
 * security filter chain are not loaded. {@link ValidationErrorHandler} is wired
 * directly as the controller advice.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Missing {@code endToEndId} → 400 with field error</li>
 *   <li>Invalid ABA routing number (non-digit / wrong length) → 400 with field error</li>
 *   <li>Amount below minimum (zero) → 400 with field error</li>
 *   <li>Fully valid message → 200 (delegates to {@link MessageRouter})</li>
 * </ul>
 */
class FedNowGatewayValidationTest {

    private MockMvc mockMvc;
    private MessageRouter messageRouter;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        messageRouter = mock(MessageRouter.class);
        CertificateManager certificateManager = mock(CertificateManager.class);
        FedNowGateway gateway = new FedNowGateway(messageRouter, certificateManager);
        mockMvc = MockMvcBuilders
                .standaloneSetup(gateway)
                .setControllerAdvice(new ValidationErrorHandler())
                .build();
    }

    @Test
    void missingEndToEndId_returns400WithFieldError() throws Exception {
        Pacs008Message msg = baseBuilder().endToEndId(null).build();

        mockMvc.perform(post("/fednow/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(msg)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("ISO 20022 message validation failed"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'endToEndId')]").exists());
    }

    @Test
    void invalidDebtorRoutingNumber_returns400WithFieldError() throws Exception {
        Pacs008Message msg = baseBuilder().debtorAgentRoutingNumber("NOTANUM").build();

        mockMvc.perform(post("/fednow/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(msg)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'debtorAgentRoutingNumber')]").exists());
    }

    @Test
    void invalidCreditorRoutingNumber_returns400WithFieldError() throws Exception {
        Pacs008Message msg = baseBuilder().creditorAgentRoutingNumber("12345").build();  // too short

        mockMvc.perform(post("/fednow/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(msg)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'creditorAgentRoutingNumber')]").exists());
    }

    @Test
    void zeroAmount_returns400WithFieldError() throws Exception {
        Pacs008Message msg = baseBuilder().interbankSettlementAmount(BigDecimal.ZERO).build();

        mockMvc.perform(post("/fednow/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(msg)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'interbankSettlementAmount')]").exists());
    }

    @Test
    void missingCreditorName_returns400WithFieldError() throws Exception {
        Pacs008Message msg = baseBuilder().creditorName(null).build();

        mockMvc.perform(post("/fednow/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(msg)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'creditorName')]").exists());
    }

    @Test
    void validMessage_returns200() throws Exception {
        when(messageRouter.routeOutbound(any()))
                .thenReturn(ResponseEntity.ok(Pacs002Message.accepted("E2E-001", "TXN-001")));

        mockMvc.perform(post("/fednow/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validMessage())))
                .andExpect(status().isOk());
    }

    private Pacs008Message validMessage() {
        return baseBuilder().build();
    }

    private Pacs008Message.Pacs008MessageBuilder baseBuilder() {
        return Pacs008Message.builder()
                .messageId("MSG-20240115-001")
                .endToEndId("E2E-20240115-001")
                .transactionId("TXN-20240115-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal("1000.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("123456789")
                .creditorAccountNumber("987654321")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones");
    }
}
