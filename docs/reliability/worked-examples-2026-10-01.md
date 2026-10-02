# Three synthetic reliability exercises — tested source `99b7ffb`

Run from a clean checkout with the prerequisites and dependencies in the [evaluator guide](evaluator-guide.md):

```sh
python3 scripts/evaluate.py --integration --negative-control --external
```

This command produced the [October 1 final-source raw bundle](evidence/practical-2026-10-01/final-full/). The scenarios use generated identifiers and a local JSON rail simulator; values are minor USD units. `serviceStatus=SETTLED` is the simulator's outcome. A local `POST` effect and ledger decrease are a **separate** observation; neither establishes a live FedNow settlement or real core posting.

## S02 — accepted remotely, confirmation lost

**Fault schedule and invariant.** Seed a synthetic exclusive account with 100,000; reserve 10,000; make the simulator record `SETTLED` but discard the submit response. The local operation must stay `OUTCOME_UNKNOWN`, retain its hold, leave the ledger at 100,000 and avoid a second submission on a duplicate request. Advance the synthetic inquiry clock; only then may it use the simulator's status to post once.

**Observed timeline.** The archived [S02 trace at line 127](evidence/practical-2026-10-01/final-full/openfednow-sync.json#L127) ends `SETTLED` with one remote submission, `HOLD` and `POST`, ledger 90,000 and held balance zero. The [intermediate observation at line 184](evidence/practical-2026-10-01/final-full/openfednow-sync.json#L184) records `OUTCOME_UNKNOWN`, unchanged ledger, held 10,000, and a duplicate HTTP 202 that returned the same operation ID. The [runner assertions](../../harness/run.py) require each intermediate and final state; the full result is `PASS`. An integration engineer can inspect whether a lost network reply leaves a reviewable obligation instead of releasing spendable funds or sending again. Limitation: the status source and inquiry timing are synthetic; the fixture is not authenticated rail status and its SQL balance is not an institution-wide core reservation.

## S06 — concurrent duplicate requests

**Fault schedule and invariant.** With 100,000 available, hold the first remote effect at a simulator barrier, send an identical second business request, then release the barrier. Both calls must identify one durable operation; at most one HOLD, one POST and one remote submission may result.

**Observed timeline.** [S06 begins at line 319](evidence/practical-2026-10-01/final-full/openfednow-sync.json#L319). The [concurrency record at line 376](evidence/practical-2026-10-01/final-full/openfednow-sync.json#L376) shows the same operation ID on both calls, duplicate HTTP 202 while the first was still `SUBMITTING`, and the named barrier. The final effect rows are `HOLD` and `POST` for 10,000, ledger 90,000, held zero, simulator submit count one; the assertion passed. The separate [S16 trace at line 524](evidence/practical-2026-10-01/final-full/openfednow-sync.json#L524) races *distinct* 70,000 payments: one wins with a POST and ledger 30,000, the other receives HTTP 409 and has no remote effect (HTTP 404). This distinction matters when an integration engineer separates idempotent retries from competing claims on available funds. Limitation: these are one deterministic barrier schedule and one simultaneous-reservation schedule, not a concurrency stress distribution or all-channel funds control.

## S05 — process death after remote effect

**Fault schedule and invariant.** The separate runner starts Java, seeds and reserves 10,000, and sends to the independent simulator. After the simulator records the remote effect but before Java persists the response, it sends `SIGKILL`, restarts Java, and advances the synthetic inquiry. Restart must retain the intent and hold, avoid resubmission, and reconcile to one local POST.

**Observed timeline.** [Before inquiry at line 4](evidence/practical-2026-10-01/final-full/restart-s05.json#L4), the recovered operation is `SUBMITTING`; the [account at line 26](evidence/practical-2026-10-01/final-full/restart-s05.json#L26) still has ledger 100,000 and held 10,000. The [final snapshot at line 35](evidence/practical-2026-10-01/final-full/restart-s05.json#L35) has `SETTLED`, one simulator submit, `HOLD` and `POST`, ledger 90,000, held zero. The exact [kill boundary is recorded at line 157](evidence/practical-2026-10-01/final-full/restart-s05.json#L157); the assertion passed. The same trace separately shows an aged `INVESTIGATION` and its hold surviving restart. An integration engineer can see why persisting a possible-send intent before transport avoids treating a missing reply as a safe retry. Limitation: this is one chosen kill boundary against a synthetic rail and SQL fixture, not a complete crash-boundary population or a live recovery procedure.

A separate [S11 two-worker trace](evidence/practical-2026-10-01/final-full/two-worker-s11.json#L1) checks lease expiry and fences a stale worker before one POST. Its two JVMs share this local PostgreSQL, not a bank deployment. See the [practical report](practical-evaluation-2026-10-01.md) for comparison, version integrity and unresolved dependencies.
