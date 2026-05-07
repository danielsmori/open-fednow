package io.openfednow;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI metadata for the OpenFedNow API.
 *
 * <p>The generated specification is available at:
 * <ul>
 *   <li>{@code GET /v3/api-docs} — OpenAPI 3 JSON</li>
 *   <li>{@code GET /swagger-ui.html} — interactive Swagger UI</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openFedNowApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpenFedNow API")
                        .version("0.1.0")
                        .description("""
                                Open-source middleware API for connecting legacy core banking systems \
                                to the Federal Reserve FedNow Instant Payment Service.

                                Supports real-time ISO 20022 payment processing with a Shadow Ledger \
                                that maintains 24/7 availability during core banking maintenance windows. \
                                Vendor adapters are available for Fiserv, FIS, and Jack Henry core \
                                platforms; the Sandbox adapter enables full local development without \
                                a real vendor connection.""")
                        .contact(new Contact()
                                .name("OpenFedNow")
                                .url("https://github.com/danielsmori/open-fednow"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .externalDocs(new ExternalDocumentation()
                        .description("ISO 20022 Message Catalogue")
                        .url("https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive"));
    }
}
