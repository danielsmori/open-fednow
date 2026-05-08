#!/usr/bin/env bash
# OpenFedNow — end-to-end sandbox demo
# Runs against a locally started app (http://localhost:8080)
#
# Usage:
#   docker-compose up -d
#   mvn spring-boot:run &   (wait for "Started OpenFedNowApplication")
#   ./demo/run-demo.sh

set -euo pipefail

BASE="http://localhost:8080"
BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
RESET='\033[0m'

header() { echo -e "\n${BOLD}${CYAN}━━━ $* ━━━${RESET}"; }
ok()     { echo -e "${GREEN}✓${RESET} $*"; }
note()   { echo -e "${YELLOW}→${RESET} $*"; }

# ── Preflight ─────────────────────────────────────────────────────────────────

header "Preflight check"
if ! curl -sf "$BASE/fednow/health" > /dev/null; then
  echo "App is not running at $BASE — start it with:"
  echo "  docker-compose up -d && mvn spring-boot:run"
  exit 1
fi
ok "App is up at $BASE"

# ── Step 1: Normal acceptance ─────────────────────────────────────────────────

header "Step 1 — Normal payment (core online)"
note "Sending \$250 pacs.008 credit transfer to ACC-DEMO-12345"

STEP1=$(curl -sf -X POST "$BASE/fednow/receive" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                  "MSG-DEMO-001",
    "endToEndId":                 "E2E-DEMO-001",
    "transactionId":              "TXN-DEMO-001",
    "interbankSettlementAmount":  250.00,
    "interbankSettlementCurrency":"USD",
    "creditorAccountNumber":      "ACC-DEMO-12345"
  }')

echo "$STEP1"
STATUS=$(echo "$STEP1" | python3 -c "import sys,json; print(json.load(sys.stdin)['transactionStatus'])")
[ "$STATUS" = "ACSC" ] && ok "ACSC — AcceptedSettlementCompleted" || { echo "FAIL: expected ACSC, got $STATUS"; exit 1; }

# ── Step 2: Rejection ─────────────────────────────────────────────────────────

header "Step 2 — Rejection scenario (insufficient funds)"
note "Sending to RJCT_FUNDS_ prefix → sandbox returns AM04"

STEP2=$(curl -sf -X POST "$BASE/fednow/receive" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                  "MSG-DEMO-002",
    "endToEndId":                 "E2E-DEMO-002",
    "transactionId":              "TXN-DEMO-002",
    "interbankSettlementAmount":  250.00,
    "interbankSettlementCurrency":"USD",
    "creditorAccountNumber":      "RJCT_FUNDS_ACC-DEMO-67890"
  }')

echo "$STEP2"
STATUS=$(echo "$STEP2" | python3 -c "import sys,json; print(json.load(sys.stdin)['transactionStatus'])")
CODE=$(echo "$STEP2"  | python3 -c "import sys,json; print(json.load(sys.stdin)['rejectReasonCode'])")
[ "$STATUS" = "RJCT" ] && [ "$CODE" = "AM04" ] \
  && ok "RJCT / AM04 — rejected, insufficient funds" \
  || { echo "FAIL: expected RJCT/AM04, got $STATUS/$CODE"; exit 1; }

# ── Step 3: Provisional acceptance ───────────────────────────────────────────

header "Step 3 — Provisional acceptance (ACSP)"
note "Sending to TOUT_ prefix → SyncAsyncBridge returns ACSP immediately"

STEP3=$(curl -sf -X POST "$BASE/fednow/receive" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                  "MSG-DEMO-003",
    "endToEndId":                 "E2E-DEMO-003",
    "transactionId":              "TXN-DEMO-003",
    "interbankSettlementAmount":  300.00,
    "interbankSettlementCurrency":"USD",
    "creditorAccountNumber":      "TOUT_ACC-DEMO-12345"
  }')

echo "$STEP3"
STATUS=$(echo "$STEP3" | python3 -c "import sys,json; print(json.load(sys.stdin)['transactionStatus'])")
[ "$STATUS" = "ACSP" ] && ok "ACSP — AcceptedSettlementInProcess (provisional)" || { echo "FAIL: expected ACSP, got $STATUS"; exit 1; }

# ── Step 4: Reconciliation ────────────────────────────────────────────────────

header "Step 4 — Reconciliation"
note "POST /admin/reconcile — finds unconfirmed Shadow Ledger entries and marks them confirmed"

STEP4=$(curl -sf -X POST "$BASE/admin/reconcile")

echo "$STEP4"
SUCCESS=$(echo "$STEP4" | python3 -c "import sys,json; print(json.load(sys.stdin)['reconciliationSuccessful'])")
[ "$SUCCESS" = "True" ] && ok "reconciliationSuccessful = true" || { echo "FAIL: reconciliation reported failure"; exit 1; }

# ── Summary ───────────────────────────────────────────────────────────────────

echo -e "\n${BOLD}${GREEN}All steps passed.${RESET}"
echo ""
echo "  ACSC  — normal payment accepted by core"
echo "  RJCT  — rejected by core (AM04 insufficient funds)"
echo "  ACSP  — provisional acceptance (core timeout / maintenance window)"
echo "  ✓     — reconciliation cycle clean"
echo ""
echo "Sandbox prefix cheatsheet:"
echo "  RJCT_FUNDS_   → RJCT / AM04   (insufficient funds)"
echo "  RJCT_ACCT_    → RJCT / AC01   (invalid account)"
echo "  RJCT_CLOSED_  → RJCT / AC04   (closed account)"
echo "  TOUT_         → ACSP           (core timeout)"
echo "  PEND_         → ACSP           (core pending)"
