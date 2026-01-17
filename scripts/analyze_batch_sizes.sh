#!/bin/bash
# Script to analyze batch sizes from Prometheus

PROMETHEUS_URL="http://localhost:9090"

echo "=== Batch Size Analysis ==="
echo ""

# Get total rows and batches
TOTAL_ROWS=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=crdb_batch_rows_total" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)
TOTAL_BATCHES=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=crdb_batches_total_total" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)

echo "Total Rows Processed: ${TOTAL_ROWS}"
echo "Total Batches Dispatched: ${TOTAL_BATCHES}"

if [ "${TOTAL_BATCHES}" != "0" ] && [ "${TOTAL_BATCHES}" != "" ]; then
    AVG_BATCH_SIZE=$(echo "scale=2; ${TOTAL_ROWS} / ${TOTAL_BATCHES}" | bc)
    echo "Average Batch Size: ${AVG_BATCH_SIZE} items"
    echo ""
    
    if (( $(echo "${AVG_BATCH_SIZE} < 2" | bc -l) )); then
        echo "❌ CRITICAL: Average batch size is ${AVG_BATCH_SIZE} (expected ~50)!"
        echo "   This suggests items are being dispatched immediately (1 item per batch)"
    elif (( $(echo "${AVG_BATCH_SIZE} < 10" | bc -l) )); then
        echo "⚠️  WARNING: Average batch size is ${AVG_BATCH_SIZE} (expected ~50)"
        echo "   Batching is not working efficiently"
    elif (( $(echo "${AVG_BATCH_SIZE} > 100" | bc -l) )); then
        echo "⚠️  WARNING: Average batch size is ${AVG_BATCH_SIZE} (expected ~50)"
        echo "   Batches are larger than expected - may indicate accumulation issues"
    else
        echo "✅ Average batch size looks reasonable (${AVG_BATCH_SIZE})"
    fi
else
    echo "⚠️  No batch data available"
fi

echo ""
echo "=== Vortex Metrics ==="
VORTEX_REQUESTS=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=vortex_requests_submitted_total" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)
VORTEX_BATCHES=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=vortex_batches_dispatched_total" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)

echo "Vortex Requests Submitted: ${VORTEX_REQUESTS}"
echo "Vortex Batches Dispatched: ${VORTEX_BATCHES}"

if [ "${VORTEX_BATCHES}" != "0" ] && [ "${VORTEX_BATCHES}" != "" ]; then
    VORTEX_AVG=$(echo "scale=2; ${VORTEX_REQUESTS} / ${VORTEX_BATCHES}" | bc)
    echo "Vortex Avg Items/Batch: ${VORTEX_AVG}"
    echo ""
    
    if (( $(echo "${VORTEX_AVG} < 2" | bc -l) )); then
        echo "❌ CRITICAL: Vortex is dispatching 1 item per batch!"
    elif (( $(echo "${VORTEX_AVG} < 10" | bc -l) )); then
        echo "⚠️  WARNING: Vortex batches are small (${VORTEX_AVG} items/batch)"
    else
        echo "✅ Vortex batching looks reasonable (${VORTEX_AVG} items/batch)"
    fi
fi

echo ""
echo "=== Comparison ==="
if [ "${TOTAL_BATCHES}" != "0" ] && [ "${VORTEX_BATCHES}" != "0" ]; then
    echo "Backend Batches: ${TOTAL_BATCHES}"
    echo "Vortex Batches: ${VORTEX_BATCHES}"
    if [ "${TOTAL_BATCHES}" = "${VORTEX_BATCHES}" ]; then
        echo "✅ Batch counts match"
    else
        echo "⚠️  Batch counts don't match - may indicate metric collection issues"
    fi
fi

