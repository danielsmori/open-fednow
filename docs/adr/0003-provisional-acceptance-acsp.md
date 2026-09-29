# ADR-0003: ACSP-shaped provisional response in the sandbox

## Status

Historical reference decision. The local ACSP-shaped response is **not verified** as valid for a live FedNow flow. The [Federal Reserve readiness guide](https://explore.fednow.org/resources/readiness-guide-understanding-the-payment-timeout-clock.pdf) discusses acceptance without posting using ACWP; applicable current procedures and credentialed message specifications must govern a real adaptation.

## Context and decision

The legacy `SyncAsyncBridge` has a bounded core-shaped call and can return an ACSP-shaped local status when the fixture is slow or unavailable. `AvailabilityBridge` can queue inbound work for later reconciliation. These mechanisms let the demo explore delayed core processing, but they cannot prove that a service settlement status or participant posting status may be emitted in the same way on a live network.

The Redis Shadow Ledger is only a local cached balance. Redis, SQL, core and rail effects are separate, and an external debit channel can invalidate its apparent availability. A subsequent core rejection cannot be undone by merely fabricating a payment return. `/fednow/return` is disabled by default because no durable return lifecycle exists.

## Consequences

The fixture's response timing, status code and follow-up actions are test inputs, not FedNow rules or a measured participant response. See [reliability scope](../reliability/scope.md), [known limitations](../known-limitations.md), and [ADR-0010](0010-bridge-mode-fraud-risk.md).
