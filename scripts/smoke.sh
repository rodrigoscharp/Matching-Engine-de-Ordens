#!/usr/bin/env bash
# Smoke test for Athena Matching Engine.
# Requires: curl, jq, and a running instance (make infra-up && make run).
# Usage: ./scripts/smoke.sh [BASE_URL]

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
PASS=0
FAIL=0

ok()   { echo "  [PASS] $*"; ((PASS++)); }
fail() { echo "  [FAIL] $*" >&2; ((FAIL++)); }

check_json_field() {
  local label="$1" json="$2" field="$3" expected="$4"
  local actual
  actual=$(echo "$json" | jq -r "$field" 2>/dev/null || echo "<jq-error>")
  if [[ "$actual" == "$expected" ]]; then
    ok "$label"
  else
    fail "$label — expected '$expected', got '$actual'"
  fi
}

echo ""
echo "Athena smoke test → $BASE_URL"
echo "────────────────────────────────────────────"

# ── 1. Health check ────────────────────────────────────────────────────────────
echo "[1] Health"
HEALTH=$(curl -sf "$BASE_URL/actuator/health" || echo "{}")
check_json_field "actuator/health is UP" "$HEALTH" ".status" "UP"

# ── 2. Place a limit buy order ─────────────────────────────────────────────────
echo "[2] Place limit buy"
BUY_RESP=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-buy-$(date +%s%N)" \
  -d '{"symbol":"PETR4","side":"BUY","type":"LIMIT","price":"10.50","quantity":"100"}')
BUY_ORDER_ID=$(echo "$BUY_RESP" | jq -r '.orderId' 2>/dev/null || echo "")
if [[ -n "$BUY_ORDER_ID" && "$BUY_ORDER_ID" != "null" ]]; then
  ok "Place limit buy → orderId=$BUY_ORDER_ID"
else
  fail "Place limit buy — no orderId in response: $BUY_RESP"
fi

# ── 3. Idempotency: repeat the same buy with same key ──────────────────────────
echo "[3] Idempotency"
IDEMP_KEY="smoke-idemp-$(date +%s%N)"
R1=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMP_KEY" \
  -d '{"symbol":"VALE3","side":"BUY","type":"LIMIT","price":"5.00","quantity":"50"}')
ID1=$(echo "$R1" | jq -r '.orderId')
R2=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMP_KEY" \
  -d '{"symbol":"VALE3","side":"BUY","type":"LIMIT","price":"5.00","quantity":"50"}')
ID2=$(echo "$R2" | jq -r '.orderId')
if [[ "$ID1" == "$ID2" ]]; then
  ok "Idempotency: same key returns same orderId"
else
  fail "Idempotency: got different orderIds ($ID1 vs $ID2)"
fi

# ── 4. Book snapshot (non-empty) ───────────────────────────────────────────────
echo "[4] Book snapshot"
BOOK=$(curl -sf "$BASE_URL/api/v1/books/PETR4" || echo "{}")
BIDS=$(echo "$BOOK" | jq '.bids | length' 2>/dev/null || echo "0")
if [[ "$BIDS" -ge 1 ]]; then
  ok "Book snapshot has at least 1 bid level"
else
  fail "Book snapshot bids is empty or missing (bids=$BIDS)"
fi
check_json_field "Book snapshot symbol" "$BOOK" ".symbol" "PETR4"

# ── 5. Empty book snapshot for unknown symbol ──────────────────────────────────
echo "[5] Empty book"
EMPTY=$(curl -sf "$BASE_URL/api/v1/books/UNKNWN" || echo "{}")
check_json_field "Empty book returns 200 with empty bids" "$EMPTY" ".bids | length" "0"

# ── 6. Missing Idempotency-Key returns 400 ────────────────────────────────────
echo "[6] Validation"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/orders" \
  -H "Content-Type: application/json" \
  -d '{"symbol":"PETR4","side":"BUY","type":"LIMIT","price":"10.00","quantity":"10"}')
if [[ "$HTTP_CODE" == "400" ]]; then
  ok "Missing Idempotency-Key → 400"
else
  fail "Missing Idempotency-Key → expected 400, got $HTTP_CODE"
fi

# ── 7. Cancel the buy order ────────────────────────────────────────────────────
echo "[7] Cancel"
if [[ -n "$BUY_ORDER_ID" && "$BUY_ORDER_ID" != "null" ]]; then
  CANCEL_RESP=$(curl -sf -X DELETE "$BASE_URL/api/v1/orders/$BUY_ORDER_ID" \
    -H "Idempotency-Key: smoke-cancel-$(date +%s%N)")
  check_json_field "Cancel resting order" "$CANCEL_RESP" ".cancelled" "true"
else
  fail "Cancel skipped — no valid orderId from step 2"
fi

# ── 8. Matching: place sell at same price ──────────────────────────────────────
echo "[8] Matching"
BUY_KEY="smoke-match-buy-$(date +%s%N)"
SELL_KEY="smoke-match-sell-$(date +%s%N)"
curl -sf -X POST "$BASE_URL/api/v1/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $BUY_KEY" \
  -d '{"symbol":"ITUB4","side":"BUY","type":"LIMIT","price":"8.00","quantity":"100"}' > /dev/null
SELL_RESP=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $SELL_KEY" \
  -d '{"symbol":"ITUB4","side":"SELL","type":"LIMIT","price":"8.00","quantity":"100"}')
SELL_ID=$(echo "$SELL_RESP" | jq -r '.orderId' 2>/dev/null || echo "")
ITUB_BOOK=$(curl -sf "$BASE_URL/api/v1/books/ITUB4" || echo "{}")
ITUB_BIDS=$(echo "$ITUB_BOOK" | jq '.bids | length')
if [[ "$ITUB_BIDS" == "0" ]]; then
  ok "After matching: book is empty (both orders fully filled)"
else
  fail "After matching: expected empty book, got bids=$ITUB_BIDS"
fi

echo ""
echo "────────────────────────────────────────────"
echo "Results: $PASS passed, $FAIL failed"
echo ""
[[ "$FAIL" -eq 0 ]]
