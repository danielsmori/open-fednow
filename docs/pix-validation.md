# PIX comparison: context, not validation

Brazil's PIX and the U.S. FedNow Service both motivate questions about always-available payment processing and legacy-core integration. Their operating rules, message profiles, access controls and participant obligations differ. A pattern considered useful for one rail cannot be assumed valid for the other.

OpenFedNow is an independent synthetic reference repository. It does **not** contain evidence that this code, its five-layer architecture, the Shadow Ledger, or an 87/13 shared-to-adapter split was deployed or validated in a PIX production environment. It also does not substantiate any person's role in a proprietary PIX project. Such experience, if relevant, needs separate primary evidence and attribution; it cannot be inferred from this codebase.

The repo's tested claim is narrower: under declared local faults, its [SQL reliability service](reliability/architecture.md) and [external oracle](reliability/evaluation-report.md) retain ambiguous obligations, exercise recovery and observe synthetic financial effects. This does not establish PIX or FedNow interoperability, institution adoption, or national impact.

For official background on the two services, see [Banco Central do Brasil's PIX overview](https://www.bcb.gov.br/estabilidadefinanceira/pix) and [Federal Reserve FedNow resources](https://www.frbservices.org/resources/financial-services/fednow-service-resources/).
