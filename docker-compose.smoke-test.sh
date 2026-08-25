#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for banking-service to be reachable..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/transfers/does-not-exist -o /dev/null -w '%{http_code}' | grep -q 404; then
    break
  fi
  sleep 2
done

echo "Submitting an auto-release transfer (below policy threshold)..."
RESPONSE=$(curl -sf -X POST http://localhost:8080/transfers \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{"makerId":"maker-1","fromAccount":"ACC-FUNDED","toAccount":"ACC-DEST","amountMinorUnits":100000,"currency":"AED"}')
echo "Submit response: $RESPONSE"

TRANSFER_ID=$(echo "$RESPONSE" | grep -o '"transferId":"[^"]*"' | cut -d'"' -f4)

echo "Polling for release (outbox relay runs every 2s)..."
for i in $(seq 1 15); do
  STATE=$(curl -sf http://localhost:8080/transfers/"$TRANSFER_ID" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
  echo "  transfer state: $STATE"
  if [ "$STATE" = "RELEASED" ]; then
    echo "SMOKE TEST PASSED: transfer released end-to-end"
    exit 0
  fi
  sleep 2
done

echo "SMOKE TEST FAILED: transfer did not reach RELEASED"
exit 1
