package io.openfednow.reliability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/** Same-origin console access to the separate synthetic rail, enabled with fixture APIs only. */
@RestController
@Profile("!prod")
@ConditionalOnProperty(name = "openfednow.reliability.fixture-api-enabled", havingValue = "true")
@RequestMapping("/reference/v1/fixtures/rail")
public class ReliabilityDemoRailController {
    private final RestTemplate http;
    private final String endpoint;

    public ReliabilityDemoRailController(
            @Value("${openfednow.reliability.synthetic-rail-url:}") String endpoint,
            @Value("${openfednow.reliability.transport-timeout-millis:1500}") int timeoutMillis) {
        this.http = new RestTemplate();
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        this.http.setRequestFactory(factory);
        this.endpoint = endpoint.replaceAll("/+$", "");
    }

    @PostMapping("/control/{messageId}")
    public ResponseEntity<Map> control(@PathVariable String messageId,
                                       @RequestBody LostReplyControl control) {
        if (endpoint.isBlank()) return ResponseEntity.status(503).build();
        Map body = http.postForObject(endpoint + "/control/{messageId}",
                Map.of("status", "SETTLED", "lose_response", control.loseResponse()),
                Map.class, messageId);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/payments/{messageId}")
    public ResponseEntity<Map> payment(@PathVariable String messageId) {
        if (endpoint.isBlank()) return ResponseEntity.status(503).build();
        return ResponseEntity.ok(http.getForObject(
                endpoint + "/payments/{messageId}", Map.class, messageId));
    }

    public record LostReplyControl(boolean loseResponse) { }
}
