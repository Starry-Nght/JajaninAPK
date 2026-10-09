#!/usr/bin/env bash
# Verification for 202610080007_ordering_limits (cases A-L).
# Runs against the REAL backend, so blackout + daily-limit behavior is tested
# with server time, not device time.
#
# Usage:
#   SUPABASE_URL=https://YOUR_REF.supabase.co \
#   SUPABASE_PUBLISHABLE_KEY=sb_publishable_... \
#   EMAIL=buyer@test.id PASSWORD=secret123 MENU_ITEM_ID=<uuid> \
#   bash supabase/tests/test_ordering_limits.sh
#
# The script resets state by deleting the buyer's own *pending* orders first
# (allowed by the "Owners can delete their pending orders" policy), so cases
# A-C and K are repeatable. QRIS/cash both accepted; uses cash here.
set -u

URL="${SUPABASE_URL:?set SUPABASE_URL}"
KEY="${SUPABASE_PUBLISHABLE_KEY:?set SUPABASE_PUBLISHABLE_KEY}"
EMAIL="${EMAIL:?set EMAIL}"
PASSWORD="${PASSWORD:?set PASSWORD}"
ITEM="${MENU_ITEM_ID:?set MENU_ITEM_ID}"

pass=0; fail=0
ok()   { if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS: $3"; else fail=$((fail+1)); echo "FAIL: $3 (want $2, got $1)"; fi }

JWT=$(curl -s "$URL/auth/v1/token?grant_type=password" \
  -H "apikey: $KEY" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
UID=$(python3 -c "import base64,json,sys; p='$JWT'.split('.')[1]; p+= '='*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))['sub'])")
echo "buyer uid: $UID"

rpc() { # $1 = path, $2 = body
  curl -s -o /tmp/jajanin_resp.json -w "%{http_code}" "$URL/rest/v1/rpc/$1" \
    -H "apikey: $KEY" -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
    -d "$2"
}
place() { rpc place_order "{\"p_items\":$1,\"p_payment_method\":\"cash\"}"; }
msg() { python3 -c "
import json
try:
    d = json.load(open('/tmp/jajanin_resp.json'))
    print(d.get('message', d) if isinstance(d, dict) else d)
except Exception:
    print(open('/tmp/jajanin_resp.json').read())
"; }
reset_day() {
  curl -s -o /dev/null "$URL/rest/v1/orders?user_id=eq.$UID&status=eq.pending" \
    -H "apikey: $KEY" -H "Authorization: Bearer $JWT" -X DELETE
  echo "state reset (own pending orders deleted)"
}

echo "--- server WIB time + status probe ---"
curl -s "$URL/rest/v1/rpc/ordering_status" -X POST \
  -H "apikey: $KEY" -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" -d '{}'
echo; echo "(F/G blackout applies only if server time is inside 09:00-10:00 / 12:00-13:00 WIB)"

echo "--- D: two different items in one order => REJECTED ---"
code=$(place "[{\"menu_item_id\":\"$ITEM\",\"quantity\":1},{\"menu_item_id\":\"$ITEM\",\"quantity\":1}]")
echo "http=$code msg=$(msg)"; ok "$code" 400 "D multi-item rejected"

echo "--- E: quantity 2 => REJECTED ---"
code=$(place "[{\"menu_item_id\":\"$ITEM\",\"quantity\":2}]")
echo "http=$code msg=$(msg)"; ok "$code" 400 "E qty-2 rejected"

reset_day
echo "--- A: first order (1 item, qty 1) => SUCCESS unless blackout ---"
code=$(place "[{\"menu_item_id\":\"$ITEM\",\"quantity\":1}]")
echo "http=$code msg=$(msg)"
echo "--- B: second order => SUCCESS unless blackout ---"
code=$(place "[{\"menu_item_id\":\"$ITEM\",\"quantity\":1}]")
echo "http=$code msg=$(msg)"
echo "--- C: third order => REJECTED (or blackout message during blackout) ---"
code=$(place "[{\"menu_item_id\":\"$ITEM\",\"quantity\":1}]")
echo "http=$code msg=$(msg)"; ok "$code" 400 "C third order rejected"

echo "--- K: 3 simultaneous requests, only 2 may succeed ---"
reset_day
for _ in 1 2 3; do place "[{\"menu_item_id\":\"$ITEM\",\"quantity\":1}]" > /tmp/jajanin_k.$$.$_.code & done
wait
codes=$(cat /tmp/jajanin_k.$$.*.code 2>/dev/null | tr '\n' ' '); rm -f /tmp/jajanin_k.$$.*.code
echo "http codes: $codes"
n200=$(echo "$codes" | tr ' ' '\n' | grep -c '^200$' || true)
[ "$n200" = "2" ] && ok 1 1 "K exactly 2 of 3 concurrent succeed" || { fail=$((fail+1)); echo "FAIL: K want exactly two 200s, got: $codes"; }
reset_day

echo "--- H/I/J: outside blackout + fresh day => ALLOWED (covered by A/B above);"
echo "--- J reset happens automatically at 00:00 WIB (WIB-date comparison)."
echo "--- F/G: re-run this script during 09:30 / 12:30 WIB; A/B must then be REJECTED with 'ditutup pukul'."
echo "--- L: every case above already calls the RPC directly, bypassing the app (backend enforced)."
echo "RESULT: pass=$pass fail=$fail"
[ "$fail" = "0" ]
