# Shadow Ledger

The Shadow Ledger is a real-time balance tracking system that operates independently of the core banking system. It is the component that allows a financial institution to participate in FedNow 24/7 — even when its core banking system is offline for scheduled maintenance.

For architectural context, see [architecture.md](architecture.md).

---

## The Problem

FedNow operates 24/7/365. There are no maintenance windows from FedNow's perspective — if an institution does not respond to a payment within 20 seconds, FedNow treats it as unavailable, which affects the institution's standing as a network participant.

Legacy core banking systems take scheduled maintenance windows. A typical community bank core takes 2–4 hours of maintenance nightly (often between midnight and 4am) and may have additional weekend windows for batch reconciliation. During these windows, the core cannot post transactions or return balance information.

Without a solution, a bank cannot legally be a FedNow send participant (receive-only participation is more tolerant of availability gaps, but still has constraints). This is one reason why many early FedNow participants are receive-only.

---

## Solution Architecture

The Shadow Ledger maintains a continuously updated replica of account balances in Redis — a low-latency in-memory data store chosen for its sub-millisecond read performance, which is required to complete a balance check within FedNow's 20-second window.

The Shadow Ledger is not a replacement for the core ledger. The core system remains the ledger of record. The Shadow Ledger is a satellite ledger that tracks in-flight FedNow activity and bridges the gap during core downtime.

### Normal Operation (Core Online)

```
FedNow payment arrives
    → AvailabilityBridge: core is available
    → ShadowLedger.applyDebit() — reserve funds in real time
    → SyncAsyncBridge: submit to core
    → Core confirms
    → ShadowLedger.reconcile() — confirm balance update
    → pacs.002 ACSC returned to FedNow
```

During normal operation, the Shadow Ledger tracks every FedNow transaction in real time. Its balance figures are never more than one transaction behind the core.

### Maintenance Window (Core Offline)

```
FedNow payment arrives
    → AvailabilityBridge: core is OFFLINE
    → ShadowLedger.getAvailableBalance() — check Shadow Ledger balance
    → If sufficient: ShadowLedger.applyDebit() and queue transaction in RabbitMQ
    → Return provisional pacs.002 ACSC to FedNow (within 20-second window)
    → [core comes back online] → ReconciliationService.replay()
    → All queued transactions posted to core in order
    → ShadowLedger.reconcile() — compare and alert on any discrepancy
```

From FedNow's perspective, the institution responded within the 20-second window. From the core's perspective, it receives the transaction in its correct sequence when it returns online.

### Core Return and Reconciliation

When the core returns online, the ReconciliationService:
1. Identifies all transactions queued during the offline window (ordered by timestamp)
2. Replays each transaction against the core in sequence
3. Compares the core's confirmed post-transaction balance against the Shadow Ledger's computed balance
4. Alerts (zero tolerance) on any discrepancy greater than $0.00
5. If a queued transaction is rejected by the core after being provisionally accepted: triggers Saga compensation (FedNow return payment via pacs.004)

The zero-discrepancy tolerance is intentional. In financial systems, unexplained balance differences are never acceptable, even if they resolve themselves — they are potential indicators of bugs in the reconciliation logic that must be investigated before they compound.

---

## Consistency Guarantees

**During normal operation:** The Shadow Ledger is consistent with the core within one transaction cycle. Balance reads reflect all applied FedNow transactions since the last reconciliation.

**During maintenance windows:** The Shadow Ledger is authoritative. All balance reads are served from Redis. FedNow payments are provisionally accepted based on Shadow Ledger balances, with the understanding that the core will confirm on return.

**After reconciliation:** The core ledger balance is authoritative. The Shadow Ledger is re-synchronized from the core's confirmed balances after all queued transactions are replayed.

**Failure modes:**
- *Core rejects a provisionally accepted transaction:* Saga compensation triggers a FedNow return payment (pacs.004). The debit is reversed in the Shadow Ledger. The customer's account is returned to its pre-transaction balance.
- *Reconciliation discrepancy detected:* Alert is generated. No further transactions are queued until the discrepancy is resolved and the root cause is identified.
- *Redis failure during maintenance window:* AvailabilityBridge falls back to a conservative mode: no new outbound FedNow transactions are accepted until Redis is restored. Inbound transactions continue to be queued.

---

## Implementation Details

**Balance storage:** Redis with optimistic locking. All balance updates use Redis transactions (MULTI/EXEC) with version keys to prevent race conditions under concurrent FedNow transaction load.

**Queue storage:** RabbitMQ with durable queues and persistent message delivery. Messages survive broker restarts. This ensures that transactions queued during a maintenance window are not lost if the middleware itself is restarted before the core returns.

**Balance initialization:** On framework startup, the Shadow Ledger is seeded from the core system's current balances. On core return after a maintenance window, balances are re-synchronized after the reconciliation replay completes.

**Scope:** The Shadow Ledger tracks only accounts that participate in FedNow transactions. It does not need to replicate the full core ledger — only the accounts from which FedNow payments may originate or to which they may be credited.

---

## Related Classes

- `ShadowLedger.java` — balance read/write operations (Redis-backed)
- `AvailabilityBridge.java` — mode switching between normal and maintenance-window operation; transaction queuing
- `ReconciliationService.java` — replay and synchronization after core return

## Related Documentation

- [Architecture Overview](architecture.md)
- [Anti-Corruption Layer](anti-corruption-layer.md) — how the SyncAsyncBridge interacts with the Shadow Ledger
- [Saga Pattern](saga-pattern.md) — how compensation works when the core rejects a provisionally accepted transaction
