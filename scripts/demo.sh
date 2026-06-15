#!/usr/bin/env sh
set -eu

BASE_URL="${BASE_URL:-http://localhost:8088}"

CASE_RESPONSE=$(curl -sS -X POST "$BASE_URL/api/v1/cases" \
  -H 'Content-Type: application/json' \
  -d '{
    "jiraKey": "DEMO-1234",
    "targetKey": "sample-order-service",
    "orderId": "DEMO-ORDER-1"
  }')

echo "$CASE_RESPONSE"

CASE_ID=$(printf '%s' "$CASE_RESPONSE" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -sS -X POST \
  "$BASE_URL/api/v1/cases/$CASE_ID/run-all"
