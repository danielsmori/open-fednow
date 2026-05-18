package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RtpXmlSerializer}.
 *
 * <p>Covers:
 * <ul>
 *   <li>pacs.002 XML generation — ACSC (accepted), RJCT (rejected with reason code),
 *       ACSP (provisional)</li>
 *   <li>pacs.002 XML parsing — roundtrip fidelity</li>
 *   <li>pacs.008 XML generation — structural validity and roundtrip via {@link RtpXmlParser}</li>
 *   <li>XML escaping for special characters in field values</li>
 * </ul>
 */
class RtpXmlSerializerTest {

    private final RtpXmlSerializer serializer = new RtpXmlSerializer();
    private final RtpXmlParser parser = new RtpXmlParser();

    // ── pacs.002 serialization ────────────────────────────────────────────────

    @Test
    void serializePacs002_acsc_containsRequiredFields() {
        Pacs002Message msg = Pacs002Message.builder()
                .messageId("STATUS-001")
                .originalEndToEndId("E2E-001")
                .originalTransactionId("TXN-001")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.parse("2024-01-15T10:30:00Z"))
                .build();

        String xml = serializer.serializePacs002(msg);

        assertThat(xml).contains("pacs.002.001.10");
        assertThat(xml).contains("<MsgId>STATUS-001</MsgId>");
        assertThat(xml).contains("<OrgnlEndToEndId>E2E-001</OrgnlEndToEndId>");
        assertThat(xml).contains("<OrgnlTxId>TXN-001</OrgnlTxId>");
        assertThat(xml).contains("<TxSts>ACSC</TxSts>");
        assertThat(xml).doesNotContain("StsRsnInf"); // no rejection block for ACSC
    }

    @Test
    void serializePacs002_rjct_includesReasonCode() {
        Pacs002Message msg = Pacs002Message.builder()
                .messageId("STATUS-002")
                .originalEndToEndId("E2E-002")
                .originalTransactionId("TXN-002")
                .transactionStatus(Pacs002Message.TransactionStatus.RJCT)
                .rejectReasonCode("AM04")
                .rejectReasonDescription("Insufficient funds")
                .creationDateTime(OffsetDateTime.now())
                .build();

        String xml = serializer.serializePacs002(msg);

        assertThat(xml).contains("<TxSts>RJCT</TxSts>");
        assertThat(xml).contains("<Cd>AM04</Cd>");
        assertThat(xml).contains("<AddtlInf>Insufficient funds</AddtlInf>");
    }

    @Test
    void serializePacs002_acsp_noReasonBlock() {
        Pacs002Message msg = Pacs002Message.builder()
                .messageId("STATUS-003")
                .originalEndToEndId("E2E-003")
                .originalTransactionId("TXN-003")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                .creationDateTime(OffsetDateTime.now())
                .build();

        String xml = serializer.serializePacs002(msg);

        assertThat(xml).contains("<TxSts>ACSP</TxSts>");
        assertThat(xml).doesNotContain("StsRsnInf");
    }

    // ── pacs.002 roundtrip ────────────────────────────────────────────────────

    @Test
    void pacs002Roundtrip_acsc_preservesFields() {
        Pacs002Message original = Pacs002Message.builder()
                .messageId("STATUS-RT-001")
                .originalEndToEndId("E2E-RT-001")
                .originalTransactionId("TXN-RT-001")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.parse("2024-06-01T12:00:00Z"))
                .build();

        String xml = serializer.serializePacs002(original);
        Pacs002Message parsed = serializer.parsePacs002(xml);

        assertThat(parsed.getMessageId()).isEqualTo("STATUS-RT-001");
        assertThat(parsed.getOriginalEndToEndId()).isEqualTo("E2E-RT-001");
        assertThat(parsed.getOriginalTransactionId()).isEqualTo("TXN-RT-001");
        assertThat(parsed.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    @Test
    void pacs002Roundtrip_rjct_preservesReasonCode() {
        Pacs002Message original = Pacs002Message.builder()
                .messageId("STATUS-RT-002")
                .originalEndToEndId("E2E-RT-002")
                .originalTransactionId("TXN-RT-002")
                .transactionStatus(Pacs002Message.TransactionStatus.RJCT)
                .rejectReasonCode("AC06")
                .rejectReasonDescription("Account blocked")
                .creationDateTime(OffsetDateTime.now())
                .build();

        String xml = serializer.serializePacs002(original);
        Pacs002Message parsed = serializer.parsePacs002(xml);

        assertThat(parsed.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(parsed.getRejectReasonCode()).isEqualTo("AC06");
        assertThat(parsed.getRejectReasonDescription()).isEqualTo("Account blocked");
    }

    // ── pacs.008 serialization + RtpXmlParser roundtrip ──────────────────────

    /**
     * Verifies that XML produced by {@link RtpXmlSerializer#serializePacs008} can be
     * parsed back by {@link RtpXmlParser#parse} without loss — this is the critical
     * roundtrip that makes the RTP outbound path symmetric with the inbound path.
     */
    @Test
    void pacs008Roundtrip_preservesAllFields() {
        Pacs008Message original = Pacs008Message.builder()
                .messageId("MSG-RTP-001")
                .endToEndId("E2E-RTP-001")
                .transactionId("TXN-RTP-001")
                .creationDateTime(OffsetDateTime.parse("2024-01-15T10:30:00Z"))
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal("250.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("ACC-DEB-001")
                .creditorAccountNumber("ACC-CRD-001")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .remittanceInformation("Invoice #12345")
                .build();

        String xml = serializer.serializePacs008(original);
        Pacs008Message parsed = parser.parse(xml);

        assertThat(parsed.getMessageId()).isEqualTo("MSG-RTP-001");
        assertThat(parsed.getEndToEndId()).isEqualTo("E2E-RTP-001");
        assertThat(parsed.getTransactionId()).isEqualTo("TXN-RTP-001");
        assertThat(parsed.getInterbankSettlementAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(parsed.getInterbankSettlementCurrency()).isEqualTo("USD");
        assertThat(parsed.getDebtorAgentRoutingNumber()).isEqualTo("021000021");
        assertThat(parsed.getCreditorAgentRoutingNumber()).isEqualTo("026009593");
        assertThat(parsed.getDebtorAccountNumber()).isEqualTo("ACC-DEB-001");
        assertThat(parsed.getCreditorAccountNumber()).isEqualTo("ACC-CRD-001");
        assertThat(parsed.getDebtorName()).isEqualTo("Alice Smith");
        assertThat(parsed.getCreditorName()).isEqualTo("Bob Jones");
        assertThat(parsed.getRemittanceInformation()).isEqualTo("Invoice #12345");
    }

    @Test
    void serializePacs008_containsNamespace() {
        Pacs008Message msg = buildMinimalPacs008();
        String xml = serializer.serializePacs008(msg);
        assertThat(xml).contains("pacs.008.001.08");
    }

    // ── XML escaping ──────────────────────────────────────────────────────────

    @Test
    void escape_handlesXmlSpecialCharacters() {
        assertThat(RtpXmlSerializer.escape("M&T Bank")).isEqualTo("M&amp;T Bank");
        assertThat(RtpXmlSerializer.escape("<value>")).isEqualTo("&lt;value&gt;");
        assertThat(RtpXmlSerializer.escape("it's")).isEqualTo("it&apos;s");
        assertThat(RtpXmlSerializer.escape("\"quoted\"")).isEqualTo("&quot;quoted&quot;");
    }

    @Test
    void escape_nullReturnsEmpty() {
        assertThat(RtpXmlSerializer.escape(null)).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Pacs008Message buildMinimalPacs008() {
        return Pacs008Message.builder()
                .messageId("MSG-MIN-001")
                .endToEndId("E2E-MIN-001")
                .transactionId("TXN-MIN-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("ACC-001")
                .creditorAccountNumber("ACC-002")
                .build();
    }
}
