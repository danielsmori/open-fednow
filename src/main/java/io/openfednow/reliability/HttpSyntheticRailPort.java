package io.openfednow.reliability;

import io.openfednow.iso20022.Pacs008Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** JSON contract for an independently running synthetic rail fixture only. */
public class HttpSyntheticRailPort implements ReliabilityRailPort {
    private final RestTemplate http;
    private final String endpoint;

    public HttpSyntheticRailPort(String endpoint, int timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Synthetic rail timeout must be positive");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.http = new RestTemplate(requestFactory);
        this.endpoint = endpoint.replaceAll("/+$", "");
    }

    @Override
    public RailObservation submit(Pacs008Message message, String attemptId) {
        return http.postForObject(endpoint + "/submit",
                new SyntheticSubmit(message, attemptId), RailObservation.class);
    }

    @Override
    public RailObservation inquire(PaymentView payment) {
        try {
            return http.getForObject(endpoint + "/payments/{messageId}",
                    RailObservation.class, payment.messageId());
        } catch (HttpClientErrorException.NotFound e) {
            return null; // absence is not proof of non-submission
        }
    }

    public record SyntheticSubmit(Pacs008Message message, String attemptId) { }
}
