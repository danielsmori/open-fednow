# ISO 20022-shaped reference models

OpenFedNow has Java models for `pacs.008`, `pacs.002`, `pacs.004`, `camt.056` and `camt.029`-shaped messages. The gateway and vendor-shaped adapter tests exercise selected fields and reason-code mappings. They do not validate the complete applicable FedNow or RTP message profiles, schema versions, transport, security, or live status authority. The SQL reliability harness uses a deliberately smaller JSON fixture; its `/submit` and `/payments` routes are not FedNow messages or pacs.028.

| Model or route | Local use | Boundary |
|---|---|---|
| `Pacs008Message` | Inbound reference routing and guarded synthetic send input | Live rail profile and transport unverified |
| `Pacs002Message` | Synthetic inbound status response and legacy client result | An HTTP response alone cannot prove settlement |
| `Pacs004Message` | Legacy return client shape | `/fednow/return` disabled by default; no durable return lifecycle |
| `Camt056Message` / `Camt029Message` | Inbound cancellation reference decision matrix | No outbound cancellation workflow or live rail validation |

Do not infer a valid rail message from the class name or a locally mapped reason code. A participant adapting this repository needs the applicable [FedNow operating procedures](https://www.frbservices.org/resources/rules-regulations/operating-circulars/) and credentialed message/security specifications, or the corresponding RTP materials. See [reliability scope](reliability/scope.md) and [RTP compatibility](rtp-compatibility.md).
