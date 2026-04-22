package io.openfednow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OpenFedNow — Legacy-to-Real-Time Payment Integration Framework
 *
 * <p>Open-source middleware for connecting legacy core banking systems
 * (Fiserv, Jack Henry, FIS) to the Federal Reserve's FedNow Instant
 * Payment Service.
 *
 * <p>Licensed under the Apache License 2.0.
 * See LICENSE for the full license text.
 *
 * @see <a href="https://github.com/danielsmori/open-fednow">GitHub Repository</a>
 */
@SpringBootApplication
@EnableRetry
@EnableScheduling
public class OpenFedNowApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenFedNowApplication.class, args);
    }
}
