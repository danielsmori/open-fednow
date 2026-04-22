# Shadow Ledger

The Shadow Ledger is a real-time balance tracking system that operates independently of the core banking system. It allows the institution to continue processing FedNow payments during core maintenance windows.

## Problem

FedNow operates 24/7/365. Legacy core banking systems have scheduled maintenance windows — typically nightly or weekend windows — during which they are offline. An institution whose core is offline cannot post transactions or check balances, which means it cannot meet FedNow's processing requirements.

## Solution

The Shadow Ledger maintains a continuously updated replica of account balances:

1. On startup, balances are seeded from the core system
2. Every FedNow transaction is applied to the Shadow Ledger in real time
3. During core downtime, the Shadow Ledger is the authoritative source for balance checks and payment authorization
4. When the core returns online, the ReconciliationService replays queued transactions and re-synchronizes balances
5. The core ledger remains authoritative — the Shadow Ledger defers to it whenever it is available

## Implementation

- Balances stored in Redis for sub-millisecond access
- All balance updates use optimistic locking to prevent race conditions under concurrent load
- Queued transactions stored in RabbitMQ with durable persistence
- Discrepancy detection alerts on any balance difference > $0.00 after reconciliation

## Related classes

- `ShadowLedger.java` — balance read/write operations
- `AvailabilityBridge.java` — mode switching and transaction queuing
- `ReconciliationService.java` — replay and synchronization
