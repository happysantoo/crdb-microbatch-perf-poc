#!/bin/bash
# Query Prometheus for batch size analysis

PROMETHEUS_URL="http://localhost:9090"

echo "=== Batch Size Analysis from Prometheus ==="
echo ""

# Backend metrics
echo "--- Backend Metrics ---"
ROWS=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=crdb_batch_rows_total" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)
BATCHES=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=crdb_batches_total" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)

echo "Total Rows: ${ROWS}"
echo "Total Batches: ${BATCHES}"

if [ "${BATCHES}" != "0" ] && [ "${BATCHES}" != "" ] && [ "${BATCHES}" != "None" ]; then
    AVG=$(echo "scale=2; ${ROWS} / ${BATCHES}" | bc 2>/dev/null)
    echo "Average Batch Size (Backend): ${AVG} items"
    
    if (( $(echo "${AVG} < 2" | bc -l 2>/dev/null) )); then
        echo "❌ CRITICAL: Average batch size is ${AVG} (expected ~50)!"
        echo "   Items are being dispatched immediately (1 item per batch)"
    elif (( $(echo "${AVG} > 100" | bc -l 2>/dev/null) )); then
        echo "⚠️  WARNING: Average batch size is ${AVG} (expected ~50)"
        echo "   Batches are much larger than configured - may indicate:"
        echo "   - Multiple batches combined"
        echo "   - Queue accumulation beyond batch size"
        echo "   - Metric collection error"
    else
        echo "✅ Average batch size looks reasonable"
    fi
else
    echo "⚠️  No batch data available (test may not have run)"
fi

echo ""
echo "--- Vortex Metrics ---"
VORTEX_REQUESTS=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=vortex_requests_submitted_total" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)
VORTEX_BATCHES=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=vortex_batches_dispatched_total" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)

echo "Vortex Requests: ${VORTEX_REQUESTS}"
echo "Vortex Batches: ${VORTEX_BATCHES}"

if [ "${VORTEX_BATCHES}" != "0" ] && [ "${VORTEX_BATCHES}" != "" ] && [ "${VORTEX_BATCHES}" != "None" ]; then
    VORTEX_AVG=$(echo "scale=2; ${VORTEX_REQUESTS} / ${VORTEX_BATCHES}" | bc 2>/dev/null)
    echo "Average Items/Batch (Vortex): ${VORTEX_AVG}"
    
    if (( $(echo "${VORTEX_AVG} < 2" | bc -l 2>/dev/null) )); then
        echo "❌ CRITICAL: Vortex is dispatching 1 item per batch!"
    elif (( $(echo "${VORTEX_AVG} > 100" | bc -l 2>/dev/null) )); then
        echo "⚠️  WARNING: Vortex batches are very large (${VORTEX_AVG} items)"
    else
        echo "✅ Vortex batching looks reasonable"
    fi
fi

# Vortex batch size histogram
echo ""
echo "--- Vortex Batch Size Histogram ---"
BATCH_SIZE_SUM=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=vortex_batch_size_sum" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)
BATCH_SIZE_COUNT=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=vortex_batch_size_count" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1] if d['data']['result'] else '0')" 2>/dev/null)

echo "Batch Size Sum: ${BATCH_SIZE_SUM}"
echo "Batch Size Count: ${BATCH_SIZE_COUNT}"

if [ "${BATCH_SIZE_COUNT}" != "0" ] && [ "${BATCH_SIZE_COUNT}" != "" ] && [ "${BATCH_SIZE_COUNT}" != "None" ]; then
    HIST_AVG=$(echo "scale=2; ${BATCH_SIZE_SUM} / ${BATCH_SIZE_COUNT}" | bc 2>/dev/null)
    echo "Average Batch Size (Histogram): ${HIST_AVG} items"
    echo ""
    echo "This is the most accurate measurement - it's from Vortex's internal tracking"
fi

echo ""
echo "=== Microbatching Principle Check ==="
echo "Expected: 50 items OR 50ms, whichever comes first"
echo ""
if [ "${HIST_AVG}" != "" ] && [ "${HIST_AVG}" != "0" ]; then
    if (( $(echo "${HIST_AVG} < 2" | bc -l 2>/dev/null) )); then
        echo "❌ VIOLATION: Average batch size is ${HIST_AVG} (expected ~50)"
        echo "   Principle violated: Items are dispatched immediately, not accumulated"
        echo "   Possible causes:"
        echo "   1. submitSync() triggers immediate dispatch"
        echo "   2. Vortex not respecting batchSize(50) configuration"
        echo "   3. Queue dispatching on every offer()"
    elif (( $(echo "${HIST_AVG} > 100" | bc -l 2>/dev/null) )); then
        echo "⚠️  UNUSUAL: Average batch size is ${HIST_AVG} (expected ~50)"
        echo "   Batches are much larger than configured"
        echo "   Possible causes:"
        echo "   1. Multiple batches combined in backend"
        echo "   2. Queue accumulating beyond batch size limit"
        echo "   3. Metric collection error"
    else
        echo "✅ Principle followed: Average batch size is ${HIST_AVG} (within expected range)"
    fi
fi

