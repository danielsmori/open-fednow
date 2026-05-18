package io.openfednow.acl.adapters;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.openfednow.acl.adapters.jackhenry.JackHenrySoapClient;
import io.openfednow.acl.adapters.jackhenry.JackHenryTokenManager;
import io.openfednow.acl.core.CoreBankingAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Proves that {@link JackHenryAdapter} satisfies every requirement in
 * {@link CoreBankingAdapterContractTest}.
 *
 * <p>WireMock stubs are priority-ordered so scenario-specific stubs (rejection,
 * timeout) take precedence over the default "accepted" stub. Account prefixes from
 * {@link SandboxAdapter} are matched against the SOAP request body — the adapter
 * sends the creditor account number inside the {@code <jx:AcctId>} element.
 *
 * <ul>
 *   <li>Priority 1 — {@link SandboxAdapter#PREFIX_REJECT_FUNDS} → SOAP fault 3050 (AM04)</li>
 *   <li>Priority 2 — {@link SandboxAdapter#PREFIX_REJECT_ACCOUNT} → SOAP fault 3053 (AC01)</li>
 *   <li>Priority 3 — {@link SandboxAdapter#PREFIX_REJECT_CLOSED} → SOAP fault 3051 (AC04)</li>
 *   <li>Priority 4 — {@link SandboxAdapter#PREFIX_TIMEOUT} → connection reset → TIMEOUT</li>
 *   <li>Priority 5 — default → POSTED (ACCEPTED)</li>
 * </ul>
 */
class JackHenryAdapterContractTest extends CoreBankingAdapterContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private JackHenryAdapter adapter;

    @BeforeEach
    void setUp() {
        wm.resetAll();

        SimpleClientHttpRequestFactory http11 = new SimpleClientHttpRequestFactory();
        RestClient tokenClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();
        RestClient apiClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();

        JackHenryTokenManager tokenManager = new JackHenryTokenManager(
                tokenClient, "/oauth2/token", "client-id", "client-secret", 60);
        JackHenrySoapClient soapClient = new JackHenrySoapClient(
                apiClient, tokenManager, "021000021");
        adapter = new JackHenryAdapter(soapClient);

        // OAuth token endpoint
        wm.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // TrnAdd — insufficient funds
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .withRequestBody(containing(SandboxAdapter.PREFIX_REJECT_FUNDS))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapFaultXml("3050", "Insufficient funds"))));

        // TrnAdd — invalid account
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .withRequestBody(containing(SandboxAdapter.PREFIX_REJECT_ACCOUNT))
                .atPriority(2)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapFaultXml("3053", "Invalid account number"))));

        // TrnAdd — closed account
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .withRequestBody(containing(SandboxAdapter.PREFIX_REJECT_CLOSED))
                .atPriority(3)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapFaultXml("3051", "Account is closed"))));

        // TrnAdd — timeout (connection reset → JackHenrySoapClient maps to TIMEOUT)
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .withRequestBody(containing(SandboxAdapter.PREFIX_TIMEOUT))
                .atPriority(4)
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // TrnAdd — default (normal accounts → accepted)
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(trnAddPostedXml("CONTRACT-TXN-001"))));

        // AcctBalInq — returns a fixed non-negative, 2-decimal-place balance
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("AcctBalInq"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(acctBalInqXml("50000.00"))));

        // Ping — returns a healthy response
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("Ping"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(pingXml())));
    }

    @Override
    protected CoreBankingAdapter adapter() {
        return adapter;
    }

    // ── SOAP response helpers ─────────────────────────────────────────────────

    private static String trnAddPostedXml(String trnId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <jx:TrnAddRslt>
                      <jx:TrnId>%s</jx:TrnId>
                      <jx:Status>POSTED</jx:Status>
                    </jx:TrnAddRslt>
                  </s:Body>
                </s:Envelope>""".formatted(trnId);
    }

    private static String soapFaultXml(String errCode, String errDesc) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <s:Fault>
                      <s:Code><s:Value>s:Receiver</s:Value></s:Code>
                      <s:Reason><s:Text xml:lang="en">Transaction rejected</s:Text></s:Reason>
                      <s:Detail>
                        <jx:ErrRec>
                          <jx:ErrCode>%s</jx:ErrCode>
                          <jx:ErrDesc>%s</jx:ErrDesc>
                        </jx:ErrRec>
                      </s:Detail>
                    </s:Fault>
                  </s:Body>
                </s:Envelope>""".formatted(errCode, errDesc);
    }

    private static String acctBalInqXml(String availBal) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <jx:AcctBalInqRslt>
                      <jx:AcctBal>
                        <jx:AvailBal>%s</jx:AvailBal>
                        <jx:CurBal>%s</jx:CurBal>
                      </jx:AcctBal>
                    </jx:AcctBalInqRslt>
                  </s:Body>
                </s:Envelope>""".formatted(availBal, availBal);
    }

    private static String pingXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <jx:PingRslt>
                      <jx:Status>OK</jx:Status>
                    </jx:PingRslt>
                  </s:Body>
                </s:Envelope>""";
    }
}
