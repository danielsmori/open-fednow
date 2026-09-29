# Adjacent resources and what this package adds

Reviewed September 28, 2026. This is a public-documentation comparison, not a hands-on benchmark or claim of market uniqueness.

| Resource | Documented role | Relationship to this work |
|---|---|---|
| [Federal Reserve FedNow resources](https://www.frbservices.org/resources/financial-services/fednow-service-resources/) and [DevRel](https://www.frbservices.org/resources/financial-services/fednow-service-resources/fednow-devrel/) | Official operating material and credentialed technical resources for participants/providers | This package supplies open synthetic failure scenarios; it cannot replace official specifications, institution tests or certification. |
| [Payapt services](https://payapt.com/services) and [simulator](https://payapt.com/simulator) | Payapt advertises a FedNow simulator and pre-certification testing | A payment simulator with tests already exists commercially. Public pages do not establish source availability, exact scenario parity, pricing or comparative performance; none are inferred here. |
| [Mojaloop documentation](https://docs.mojaloop.io/) and [testing toolkit](https://github.com/mojaloop/ml-testing-toolkit) | Open-source interoperable-payment software and testing assets | Open-source payment testing is not new. Mojaloop's scheme and documented interfaces differ; no feature absence is inferred without testing. |
| [Toxiproxy](https://github.com/Shopify/toxiproxy) and [Testcontainers](https://java.testcontainers.org/) | General network fault injection and disposable dependencies | The value here is the payment-specific state/effect oracle, operation linkage, and separate-target contract. Existing tools should be reused when their fault controls fit. |

OpenFedNow's contribution under test is the combination of inspectable source, explicit unknown-outcome rules, an independent remote-effects oracle, and unchanged scenarios across author-built targets. That is a technical reuse demonstration, not independent bank uptake or proof of superiority over a commercial product.
