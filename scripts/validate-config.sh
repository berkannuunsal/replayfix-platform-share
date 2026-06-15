#!/usr/bin/env sh
set -eu
BASE_URL="${BASE_URL:-http://localhost:8088}"
curl -fsS "$BASE_URL/api/v1/config/status"
echo
