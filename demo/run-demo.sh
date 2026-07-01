#!/usr/bin/env bash
# OpenFedNow — end-to-end sandbox demo
# Runs against a locally started app (http://localhost:8080)
#
# Usage:
#   docker-compose up -d
#   mvn spring-boot:run &   (wait for "Started OpenFedNowApplication")
#   ./demo/run-demo.sh
#
# The demo is deterministic: it sends fixed messageId / endToEndId /
# transactionId values and asserts on the responses. Re-running against a
# fresh Redis + H2 (the default in-memory profile) is a clean start; running
# against a live Redis with prior state will surface duplicates as ACSC
# (via the idempotency cache), which is intentional.

set -euo pipefail

BASE="http://localhost:8080"
ADMIN_AUTH="admin:changeme"
BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
RESET='\033[0m'

header() { echo -e "\n${BOLD}${CYAN}━━━ $* ━━━${RESET}"; }
ok()     { echo -e "${GREEN}✓${RESET} $*"; }
note()   { echo -e "${YELLOW}→${RESET} $*"; }
fail()   { echo -e "${RED}✗${RESET} $*"; exit 1; }

# JSON field extraction — python3 is preinstalled on macOS and every CI image.
jget()   { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

# ── Preflight ─────────────────────────────────────────────────────────────────

header "Preflight check"
if ! curl -sf "$BASE/fednow/health" > /dev/null; then
  echo "App is not running at $BASE — start it with:"
  echo "  docker-compose up -d && mvn spring-boot:run"
  exit 1
fi
ok "App is up at $BASE"

# ── Step 1: Normal inbound acceptance ─────────────────────────────────────────

header "Step 1 — Inbound normal (ACSC)"
note "\$250 pacs.008 credit transfer to ACC-DEMO-12345 (also seeds Shadow Ledger)"

STEP1=$(curl -sf -X POST "$BASE/fednow/receive" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: demo-req-inbound-001" \
  -d '{
    "messageId":                   "MSG-DEMO-001",
    "endToEndId":                  "E2E-DEMO-001",
    "transactionId":               "TXN-DEMO-001",
    "creationDateTime":            "2026-06-30T10:00:00Z",
    "numberOfTransactions":        1,
    "interbankSettlementAmount":   250.00,
    "interbankSettlementCurrency": "USD",
    "debtorAgentRoutingNumber":    "021000021",
    "creditorAgentRoutingNumber":  "026009593",
    "debtorAccountNumber":         "999888",
    "creditorAccountNumber":       "ACC-DEMO-12345",
    "debtorName":                  "Alice Smith",
    "creditorName":                "Bob Jones"
  }')
echo "$STEP1"
STATUS=$(echo "$STEP1" | jget "['transactionStatus']")
[ "$STATUS" = "ACSC" ] && ok "ACSC — AcceptedSettlementCompleted" || fail "expected ACSC, got $STATUS"

# ── Step 2: Idempotency ───────────────────────────────────────────────────────

header "Step 2 — Idempotency (duplicate suppression)"
note "Re-sending the same endToEndId — should return the cached ACSC, no new saga"

STEP2=$(curl -sf -X POST "$BASE/fednow/receive" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                   "MSG-DEMO-001-RETRY",
    "endToEndId":                  "E2E-DEMO-001",
    "transactionId":               "TXN-DEMO-001-RETRY",
    "creationDateTime":            "2026-06-30T10:00:05Z",
    "numberOfTransactions":        1,
    "interbankSettlementAmount":   250.00,
    "interbankSettlementCurrency": "USD",
    "debtorAgentRoutingNumber":    "021000021",
    "creditorAgentRoutingNumber":  "026009593",
    "debtorAccountNumber":         "999888",
    "creditorAccountNumber":       "ACC-DEMO-12345",
    "debtorName":                  "Alice Smith",
    "creditorName":                "Bob Jones"
  }')
STATUS=$(echo "$STEP2" | jget "['transactionStatus']")
[ "$STATUS" = "ACSC" ] && ok "Duplicate ACSC returned from idempotency cache" || fail "expected ACSC, got $STATUS"

# ── Step 3: Rejection scenario ────────────────────────────────────────────────

header "Step 3 — Inbound rejection (RJCT / AM04)"
note "RJCT_FUNDS_ prefix triggers sandbox to return AM04"

STEP3=$(curl -sf -X POST "$BASE/fednow/receive" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                   "MSG-DEMO-003",
    "endToEndId":                  "E2E-DEMO-003",
    "transactionId":               "TXN-DEMO-003",
    "creationDateTime":            "2026-06-30T10:00:10Z",
    "numberOfTransactions":        1,
    "interbankSettlementAmount":   250.00,
    "interbankSettlementCurrency": "USD",
    "debtorAgentRoutingNumber":    "021000021",
    "creditorAgentRoutingNumber":  "026009593",
    "debtorAccountNumber":         "999888",
    "creditorAccountNumber":       "RJCT_FUNDS_ACC-67890",
    "debtorName":                  "Alice Smith",
    "creditorName":                "Bob Jones"
  }')
STATUS=$(echo "$STEP3" | jget "['transactionStatus']")
CODE=$(echo "$STEP3"   | jget "['rejectReasonCode']")
[ "$STATUS" = "RJCT" ] && [ "$CODE" = "AM04" ] \
  && ok "RJCT / AM04 — insufficient funds" \
  || fail "expected RJCT/AM04, got $STATUS/$CODE"

# ── Step 4: Provisional acceptance ────────────────────────────────────────────

header "Step 4 — Provisional acceptance (ACSP)"
note "TOUT_ prefix simulates a slow core; SyncAsyncBridge returns ACSP immediately"

STEP4=$(curl -sf -X POST "$BASE/fednow/receive" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                   "MSG-DEMO-004",
    "endToEndId":                  "E2E-DEMO-004",
    "transactionId":               "TXN-DEMO-004",
    "creationDateTime":            "2026-06-30T10:00:15Z",
    "numberOfTransactions":        1,
    "interbankSettlementAmount":   300.00,
    "interbankSettlementCurrency": "USD",
    "debtorAgentRoutingNumber":    "021000021",
    "creditorAgentRoutingNumber":  "026009593",
    "debtorAccountNumber":         "999888",
    "creditorAccountNumber":       "TOUT_ACC-12345",
    "debtorName":                  "Alice Smith",
    "creditorName":                "Bob Jones"
  }')
STATUS=$(echo "$STEP4" | jget "['transactionStatus']")
[ "$STATUS" = "ACSP" ] && ok "ACSP — AcceptedSettlementInProcess (provisional)" || fail "expected ACSP, got $STATUS"

# ── Step 5: Outbound normal ───────────────────────────────────────────────────

header "Step 5 — Outbound send (uses balance seeded in step 1)"
note "\$100 outbound with ACC-DEMO-12345 as debtor — Shadow Ledger has \$250 from step 1"

STEP5=$(curl -sf -X POST "$BASE/fednow/send" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                   "MSG-DEMO-005",
    "endToEndId":                  "E2E-DEMO-005",
    "transactionId":               "TXN-DEMO-005",
    "creationDateTime":            "2026-06-30T10:00:20Z",
    "numberOfTransactions":        1,
    "interbankSettlementAmount":   100.00,
    "interbankSettlementCurrency": "USD",
    "debtorAgentRoutingNumber":    "021000021",
    "creditorAgentRoutingNumber":  "026009593",
    "debtorAccountNumber":         "ACC-DEMO-12345",
    "creditorAccountNumber":       "ACC-EXT-99999",
    "debtorName":                  "Bob Jones",
    "creditorName":                "Carol Williams"
  }')
STATUS=$(echo "$STEP5" | jget "['transactionStatus']")
[ "$STATUS" = "ACSC" ] && ok "Outbound ACSC — funds reserved, FedNow accepted" || fail "expected ACSC, got $STATUS"

# ── Step 6: Currency guard ────────────────────────────────────────────────────

header "Step 6 — Non-USD outbound rejected with ISO 20022 AM03"
note "Same shape as step 5 but currency=EUR — MessageRouter rejects before any side effects"

STEP6=$(curl -sf -X POST "$BASE/fednow/send" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                   "MSG-DEMO-006",
    "endToEndId":                  "E2E-DEMO-006",
    "transactionId":               "TXN-DEMO-006",
    "creationDateTime":            "2026-06-30T10:00:25Z",
    "numberOfTransactions":        1,
    "interbankSettlementAmount":   50.00,
    "interbankSettlementCurrency": "EUR",
    "debtorAgentRoutingNumber":    "021000021",
    "creditorAgentRoutingNumber":  "026009593",
    "debtorAccountNumber":         "ACC-DEMO-12345",
    "creditorAccountNumber":       "ACC-EXT-99999",
    "debtorName":                  "Bob Jones",
    "creditorName":                "Carol Williams"
  }')
STATUS=$(echo "$STEP6" | jget "['transactionStatus']")
CODE=$(echo "$STEP6"   | jget "['rejectReasonCode']")
[ "$STATUS" = "RJCT" ] && [ "$CODE" = "AM03" ] \
  && ok "RJCT / AM03 — NotAllowedCurrency (rail is USD-only)" \
  || fail "expected RJCT/AM03, got $STATUS/$CODE"

# ── Step 7: RTP rail (XML inbound) ────────────────────────────────────────────

header "Step 7 — RTP rail: XML pacs.008 to /rtp/receive"
note "Same message pattern as step 1, but XML over the RTP path (response is XML too)"

STEP7=$(curl -sf -X POST "$BASE/rtp/receive" \
  -H "Content-Type: application/xml" \
  --data-binary '<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>MSG-DEMO-RTP-007</MsgId>
      <CreDtTm>2026-06-30T10:00:30Z</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <EndToEndId>E2E-DEMO-RTP-007</EndToEndId>
        <TxId>TXN-DEMO-RTP-007</TxId>
      </PmtId>
      <IntrBkSttlmAmt Ccy="USD">175.00</IntrBkSttlmAmt>
      <DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>021000021</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>
      <CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>026009593</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>
      <Dbtr><Nm>Alice Smith</Nm></Dbtr>
      <DbtrAcct><Id><Othr><Id>999888</Id></Othr></Id></DbtrAcct>
      <Cdtr><Nm>Bob Jones</Nm></Cdtr>
      <CdtrAcct><Id><Othr><Id>ACC-DEMO-12345</Id></Othr></Id></CdtrAcct>
    </CdtTrfTxInf>
  </FIToFICstmrCdtTrf>
</Document>')
echo "$STEP7"
# XML response, no python needed; grep out the <TxSts> element (ACSC / ACSP / RJCT).
XML_STATUS=$(echo "$STEP7" | grep -oE '<TxSts>[A-Z]+</TxSts>' | head -1 | sed 's/<[^>]*>//g')
[ "$XML_STATUS" = "ACSC" ] && ok "RTP XML → ACSC — same processing pipeline, XML on the wire" || fail "expected ACSC, got $XML_STATUS"

# ── Step 8: pacs.004 outbound return ──────────────────────────────────────────

header "Step 8 — pacs.004 outbound return"
note "Returning \$50 from the earlier TXN-DEMO-001 payment"

STEP8=$(curl -sf -X POST "$BASE/fednow/return" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                    "MSG-RETURN-001",
    "creationDateTime":             "2026-06-30T10:00:35Z",
    "returnId":                     "RET-DEMO-001",
    "originalMessageId":            "MSG-DEMO-001",
    "originalEndToEndId":           "E2E-DEMO-001",
    "originalTransactionId":        "TXN-DEMO-001",
    "returnedAmount":               50.00,
    "returnedAmountCurrency":       "USD",
    "returnReasonCode":             "AC03",
    "returnReasonDescription":      "Wrong beneficiary account",
    "returningAgentRoutingNumber":  "026009593",
    "receivingAgentRoutingNumber":  "021000021"
  }')
STATUS=$(echo "$STEP8" | jget "['transactionStatus']")
[ "$STATUS" = "ACSC" ] && ok "pacs.004 accepted by FedNow (sandbox)" || fail "expected ACSC, got $STATUS"

# ── Step 9: Cancellation (camt.056 → camt.029) ────────────────────────────────

header "Step 9 — Cancellation request (camt.056)"
note "Requesting cancel of the still-provisional TXN-DEMO-004 (ACSP from step 4)"

STEP9=$(curl -sf -X POST "$BASE/fednow/cancellation" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                            "MSG-CNCL-001",
    "creationDateTime":                     "2026-06-30T10:00:40Z",
    "caseId":                               "CASE-DEMO-001",
    "originalMessageId":                    "MSG-DEMO-004",
    "originalEndToEndId":                   "E2E-DEMO-004",
    "originalTransactionId":                "TXN-DEMO-004",
    "originalInterbankSettlementAmount":    300.00,
    "originalInterbankSettlementCurrency":  "USD",
    "cancellationReasonCode":               "DUPL",
    "cancellationReasonDescription":        "Duplicate payment"
  }')
echo "$STEP9"
RESOLUTION=$(echo "$STEP9" | jget "['resolutionStatus']")
ok "camt.029 resolution=$RESOLUTION"

# ── Step 10: Reconciliation ───────────────────────────────────────────────────

header "Step 10 — Manual reconciliation cycle"
note "POST /admin/reconcile (Basic auth) — confirms unconfirmed Shadow Ledger rows"

STEP10=$(curl -sf -X POST -u "$ADMIN_AUTH" "$BASE/admin/reconcile")
echo "$STEP10"
SUCCESS=$(echo "$STEP10" | jget "['reconciliationSuccessful']")
[ "$SUCCESS" = "True" ] && ok "Reconciliation successful=true" || fail "reconciliation reported failure"

# ── Step 11: Saga snapshot with request-id correlation ────────────────────────

header "Step 11 — Saga snapshot (issue #21: requestId correlation)"
note "GET /admin/sagas/TXN-DEMO-001 — the saga initiated in step 1"

STEP11=$(curl -sf -u "$ADMIN_AUTH" "$BASE/admin/sagas/TXN-DEMO-001")
echo "$STEP11"
REQ_ID=$(echo "$STEP11" | jget "['requestId']" 2>/dev/null || echo "null")
STATE=$(echo  "$STEP11" | jget "['state']")
ok "Saga state=$STATE requestId=$REQ_ID (from X-Request-Id: demo-req-inbound-001)"

# ── Step 12: Admin audit log (PII-redacted + requestId) ───────────────────────

header "Step 12 — Admin audit log with PII redaction"
note "Priming: a request carrying a sensitive query parameter that must never persist verbatim"
# Prime call — its row is written by the audit filter AFTER the response returns,
# so we need a second GET to observe it. The 'token=leak-secret' is deliberate:
# no admin endpoint uses that param today, but the filter's PiiRedactor guards
# against any future addition that might.
curl -sf -o /dev/null -u "$ADMIN_AUTH" "$BASE/admin/audit-log?limit=1&token=leak-secret"

note "GET /admin/audit-log?limit=5 — should show the primed row with token=REDACTED"
STEP12=$(curl -sf -u "$ADMIN_AUTH" "$BASE/admin/audit-log?limit=5")
echo "$STEP12" | python3 -m json.tool

# The primed row appears as one of the top entries. Search for it and verify
# the query string was rewritten to REDACTED at persist time.
REDACTED_HIT=$(echo "$STEP12" \
  | python3 -c "import sys,json; rows=json.load(sys.stdin); print(any(r.get('queryString','') and 'token=REDACTED' in r['queryString'] for r in rows))")
if [ "$REDACTED_HIT" = "True" ]; then
  ok "Sensitive query parameter (token=...) rewritten to token=REDACTED in the audit row"
else
  fail "no audit row shows the redacted token — PII redaction is not being applied"
fi

# ── Step 13: Balance view ─────────────────────────────────────────────────────

header "Step 13 — Shadow Ledger balance"
note "GET /admin/accounts/ACC-DEMO-12345/balance — after \$250 inbound + \$100 outbound = \$150"

STEP13=$(curl -sf -u "$ADMIN_AUTH" "$BASE/admin/accounts/ACC-DEMO-12345/balance")
echo "$STEP13"
BAL=$(echo "$STEP13" | jget "['available']")
ok "Available balance: \$$BAL"

# ── Summary ───────────────────────────────────────────────────────────────────

echo -e "\n${BOLD}${GREEN}All steps passed.${RESET}"
cat <<'SUMMARY'

  Inbound normal        (ACSC)   — pacs.008 → sandbox core → accept
  Idempotency           (ACSC)   — duplicate endToEndId served from cache
  Inbound rejection     (RJCT)   — AM04 insufficient funds
  Provisional accept    (ACSP)   — SyncAsyncBridge timeout
  Outbound normal       (ACSC)   — Shadow Ledger funds check + FedNow send
  Currency guard        (RJCT)   — AM03 rejects non-USD at MessageRouter
  RTP rail              (ACSC)   — XML inbound over /rtp/receive
  pacs.004 return       (ACSC)   — outbound return via /fednow/return
  Cancellation          (camt)   — camt.056 → camt.029 resolution
  Reconciliation                 — POST /admin/reconcile
  Saga snapshot                  — GET /admin/sagas/{txnId} incl. requestId
  Audit log             (redact) — GET /admin/audit-log; token param redacted
  Balance view                   — GET /admin/accounts/{id}/balance

Sandbox prefix cheatsheet:
  RJCT_FUNDS_  → RJCT / AM04   (insufficient funds)
  RJCT_ACCT_   → RJCT / AC01   (invalid account)
  RJCT_CLOSED_ → RJCT / AC04   (closed account)
  TOUT_        → ACSP           (core timeout)
  PEND_        → ACSP           (core pending)
SUMMARY
