# TigerBeetle-backed synthetic S16: local evidence

Evaluated source revision: `59d7593a644492d1bbf8f5bb07ec8b04baa8edeb` on `feature/tigerbeetle-s16-portability`, based on main `cf449895eb38bacdf21369405f441c75940900a5`. The initial implementation commit was `9a22e85b4c95799a868bc68146533f0051479509`; the evaluated revision includes the address and pending-retry guard fixes. The server was **TigerBeetle 0.17.9+cc1c06a** (macOS universal release binary); the Python SDK was **tigerbeetle 0.17.9** in a disposable local virtual environment. One self-hosted development replica ran on loopback with fresh temporary data for each invocation. The target and rail were both project-built; accounts and payments were synthetic.

## Observed runs

| Run | Command | Result and observation source |
| --- | --- | --- |
| Published S16, unchanged profile and oracle | `target/tigerbeetle/.venv/bin/python harness/smoke_tigerbeetle.py --binary target/tigerbeetle/tigerbeetle --output target/tigerbeetle/s16-report.json` | **PASS**. TigerBeetle account/transfer lookups supplied 30,000 booked minor units, zero held units, one HOLD and one POST. The second $700 request received HTTP 409. The independent rail simulator recorded one submission for the winner and no payment for the refused request. [Profile report](s16-profile.json). |
| Pending contention, same invocation | Automatically run after S16 | **PASS**. The first 70,000-unit reservation remained pending when the second reached TigerBeetle; the ledger refused the second before the first posted. The first then posted, leaving 30,000 booked and zero held. [Contention report](contention.json). |
| Additional S01 settlement and rejection | `target/tigerbeetle/.venv/bin/python harness/smoke_tigerbeetle.py --binary target/tigerbeetle/tigerbeetle --case S01 --output target/tigerbeetle/s01-report.json` | **PASS** on the final source revision. The rejection created a ledger HOLD and VOID-derived RELEASE; the final hold was zero. [Profile report](s01-profile.json). |
| Default SQLite reference smoke | `python3 harness/smoke_profile.py --all --output target/tigerbeetle/sqlite-regression.json` | S01, S02, S06, S16 **PASS** without the optional SDK or server. |
| Portable runner unit checks | `python3 -m unittest discover -s harness -p 'test_portable_profile.py'` | 8 PASS. |
| TigerBeetle result checks | `target/tigerbeetle/.venv/bin/python -m unittest discover -s harness -p 'test_tigerbeetle_ledger.py'` | 3 PASS: explicit insufficient funds, duplicate-ID mismatch, and ambiguous-result lookup behavior. |
| Local address and pending-retry guards | `python3 -m unittest discover -s harness -p 'test_reference_target_guards.py'` | 2 PASS. A replica list containing a non-loopback address and invalid ports are rejected before SDK connection. A changed payload gets 409 while the existing reservation is pending; an identical retry gets 503. |

The first exploratory S01 run **failed** after TigerBeetle voided the hold: the target marked the operation `INVESTIGATION` because the SDK's looked-up void transfer contained the original hold amount while the request had sent zero. The final implementation sends the full pending amount on the void operation and checks the returned immutable record. The subsequent S01 run passed. The initial failed run preceded the source commit above and is not represented as a passing result.

## Mapping and claim limits

- Reused unchanged: `harness/run.py` S16/S01 case logic and assertions, `harness/portable_profile.py`, profile manifest, HTTP driver contract, and separate rail simulator. The profile report records profile, runner, oracle and scenario-manifest hashes.
- Added: opt-in TigerBeetle ledger mapping, pinned SDK dependency, isolated launcher, and targeted result tests. SQLite stores payment identities, state and stable transfer-ID mappings; it does not supply `ledgerMinor`, `heldMinor` or financial-effect rows in this mode.
- TigerBeetle account and transfer lookups supplied the financial observations. The rail simulator supplied submission counts and outcomes. The application status record was observed separately.
- No Fiserv or bank core was tested. This does not show institution-wide funds control, production availability, a live FedNow send, or independent execution by an outside engineer.
- S02 lost confirmation and restart recovery across the SQLite/TigerBeetle boundary were not run or claimed. No recovery after a TigerBeetle post but before SQLite completion is implemented in this first PR.

`SHA256SUMS` pins the changed implementation files, shared profile/oracle, and the sanitized reports. No credential, real account identifier, raw ledger transfer ID, or provider payload is included in the reports.
