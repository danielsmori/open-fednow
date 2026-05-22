# ADR-0006: Event schema versioning — hybrid header and envelope

## Status

Accepted

## Context

The optional Kafka event bus publishes `PaymentEvent` instances to a topic for downstream consumers — audit pipelines, analytics, notification services. The event payload is JSON. The framework will evolve: fields will be added, enums will gain new values, and at some point a field may be renamed or its semantics will change. Consumers running on different release trains need to know which version of the schema a given message carries so they can deserialize correctly and ignore or escalate unknown variants.

Three approaches are commonly used:

1. **Header-only versioning.** Attach a Kafka message header (`X-Schema-Version`). Routing layers can filter without deserializing the body. The payload itself remains the "current" shape; older consumers fail to deserialize unfamiliar fields.

2. **Envelope versioning.** Stamp the version into the JSON payload as a field. The version travels with the payload across any system boundary, including ones that strip Kafka headers (HTTP relays, JSON-archived files, replay tools). The version is part of the durable record.

3. **External schema registry** (Confluent Schema Registry, AWS Glue, Apicurio). The registry holds the canonical schema; the payload carries a registry ID. Strong compatibility guarantees, but requires running and maintaining the registry, plus a serdes plugin in every producer and consumer.

## Decision

Carry the version in **both** the payload envelope (`schemaVersion` field on `PaymentEvent`) and the Kafka message header (`X-Schema-Version`). The payload field is the authoritative durable record; the header is a duplicate of the same value, present so routing layers can filter without deserializing.

Implementation:

- `PaymentEvent` carries a `schemaVersion` field. `PaymentEvent.create(...)` — the static factory used by `MessageRouter` — stamps `PaymentEvent.CURRENT_SCHEMA_VERSION` automatically. The canonical record constructor still accepts an explicit version so deserialization preserves whatever version the source message carried.
- `KafkaPaymentEventPublisher.publish(...)` reads `event.schemaVersion()` and writes it into the `X-Schema-Version` header on every `ProducerRecord`. The same call writes `X-Event-Type` carrying the `EventType` enum name, so consumers can route on type without deserializing the body either.
- Versioning policy:
  - **Minor bump (1.0 → 1.1):** new optional fields, new `EventType` enum values. Existing consumers continue to read the message.
  - **Major bump (1.x → 2.0):** renamed or removed fields, semantic changes to existing fields. Existing consumers must be updated.

## Alternatives Considered

**Header-only versioning.**
Lighter to implement: no payload change, no consumer payload-side migration. Rejected because headers are sometimes stripped by intermediaries — HTTP gateways, log archivers, replay tools that store the payload as a plain JSON blob. A message that has been re-emitted from a downstream system would lose its version. The payload field is the durable record that survives every relay.

**Envelope-only versioning.**
Cleanest from a "single source of truth" perspective. Rejected because deserialization is required to route on version — a downstream Kafka Streams topology or `kafka-console-consumer` filter would have to deserialize every message just to skip ones it doesn't understand. The duplicate header makes header-filtered routing trivial.

**External schema registry.**
Strong guarantee that producers and consumers see the same canonical schema. Rejected for the open-source middleware default because it requires running and maintaining the registry, plus deserializer plugins in every consumer. Institutions that have already standardized on a registry can swap in their own publisher implementation behind the `PaymentEventPublisher` interface without changing the rest of the framework.

**Avro / Protobuf binary payloads.**
Standard partner of the schema-registry approach; same trade-off — heavier dependency cost, deserializer plugin needed everywhere. The JSON payload keeps `kafka-console-consumer` debugging trivial and stays portable across language ecosystems.

## Consequences

**Positive:**
- Consumers can implement either filtering strategy: read the header for routing decisions, read the payload field for processing decisions. Both sources agree by construction (the publisher writes the same value into both).
- The version survives any relay that retains the payload, even if the relay strips headers.
- No external infrastructure is required; the framework runs the same way it always has.
- The `EventType` header makes header-only consumer filtering (e.g., "only handle rejections") possible without payload deserialization.

**Negative:**
- The version is carried twice. Producers must keep the two values in sync — handled by `KafkaPaymentEventPublisher` reading directly from the event, so no manual coordination is required, but a hand-rolled producer outside the framework would have to do the same.
- No automated compatibility enforcement. A registry would refuse to publish an incompatible schema; the hybrid approach relies on review discipline. Acceptable for a reference framework; institutions can swap in registry-backed publishing for production.

## Related

- `PaymentEvent.java` — record with `schemaVersion` field and `CURRENT_SCHEMA_VERSION` constant
- `KafkaPaymentEventPublisher.java` — writes the `X-Schema-Version` and `X-Event-Type` headers
- [docs/event-schemas/payment-event.schema.json](../event-schemas/payment-event.schema.json) — JSON Schema for `PaymentEvent` 1.0
- [docs/event-schemas/README.md](../event-schemas/README.md) — index and compatibility policy
