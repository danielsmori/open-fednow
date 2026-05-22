# Event Schemas

JSON Schema definitions for events published by the optional Kafka event bus.

| Event family | Current version | Schema |
|---|---|---|
| `PaymentEvent` | `1.0` | [`payment-event.schema.json`](payment-event.schema.json) |

Versioning policy and rationale are documented in [ADR-0006](../adr/0006-event-schema-versioning.md).

## Reading the version

Each published Kafka message carries the schema version in two places. They are always equal — `KafkaPaymentEventPublisher` reads the value from the event itself and copies it into the header — so a consumer can choose whichever source fits its plumbing.

| Source | When to use |
|---|---|
| Kafka message header `X-Schema-Version` | Routing layers and consumers that want to filter without deserializing the body. Pair with `X-Event-Type` for type-level routing. |
| Payload field `schemaVersion` | Processing layers, durable archives, replay tools. The version travels with the payload across any relay, including HTTP gateways or log archives that strip Kafka headers. |

## Compatibility policy

- **Minor bump (1.0 → 1.1):** new optional fields, new `EventType` enum values. A consumer pinned to `1.0` continues to read the message — it sees the new fields as unknown and ignores them; an unknown `EventType` must be treated as "skip and alert" rather than as an error.
- **Major bump (1.x → 2.0):** renamed or removed fields, or semantic changes to an existing field. Consumers must be updated. The header makes per-version routing possible — operators can run side-by-side consumer pools during the migration window.

Producers must never silently bump the major version without a paired ADR documenting the migration plan.
