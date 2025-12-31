#!/bin/bash

# Test script to verify AdaptiveLoadPattern TPS adjustment fixes
# This script runs a short test and monitors logs for TPS adjustment behavior

echo "=== AdaptiveLoadPattern TPS Adjustment Test ==="
echo "Starting test to verify automatic TPS adjustment..."
echo

# Kill any existing java processes for this project
echo "Cleaning up any existing processes..."
pkill -f "crdb-microbatch-load-test" || true
sleep 2

echo "Starting CockroachDB (if not already running)..."
# Try to start CockroachDB - will fail silently if already running
cockroach start-single-node --insecure --listen-addr=localhost:26257 --http-addr=localhost:8080 --store=/tmp/cockroach-test --background 2>/dev/null || echo "CockroachDB likely already running"
sleep 3

echo "Creating test database..."
cockroach sql --insecure --host=localhost:26257 -e "CREATE DATABASE IF NOT EXISTS testdb;" 2>/dev/null || true

echo
echo "Starting load test with enhanced TPS adjustment monitoring..."
echo "Look for these key indicators:"
echo "  ⬆️ RAMP UP messages - TPS increasing"
echo "  🚨 RAMP DOWN messages - TPS decreasing due to failures"
echo "  ❌ CRITICAL FAILURE RATE - Failures detected"
echo "  📊 getFailureRate() called - AdaptiveLoadPattern monitoring"
echo

# Run the application in background and capture output
java -jar build/libs/crdb-microbatch-perf-poc-1.0-SNAPSHOT.jar > test_output.log 2>&1 &
APP_PID=$!

echo "Application started (PID: $APP_PID)"
echo "Monitoring for 60 seconds..."

# Monitor the log for 60 seconds
timeout 60 tail -f test_output.log | while read line; do
    case "$line" in
        *"RAMP UP"*|*"RAMP DOWN"*|*"CRITICAL FAILURE"*|*"getFailureRate"*|*"AdaptiveLoadPattern"*)
            echo "[$(date +'%H:%M:%S')] $line"
            ;;
    esac
done

echo
echo "Test completed. Stopping application..."
kill $APP_PID 2>/dev/null || true
wait $APP_PID 2>/dev/null || true

echo
echo "=== Test Results Summary ==="
echo "Checking for key indicators in logs..."

echo
echo "TPS Changes detected:"
grep -c "TPS:" test_output.log || echo "0"

echo
echo "Ramp Down events (should be > 0 if working correctly):"
grep -c "RAMP DOWN" test_output.log || echo "0"

echo
echo "Failure rate monitoring (should be > 0):"
grep -c "getFailureRate()" test_output.log || echo "0"

echo
echo "Critical failure rates detected:"
grep -c "CRITICAL FAILURE RATE" test_output.log || echo "0"

echo
echo "Full log saved to: test_output.log"
echo "Review the log for detailed TPS adjustment behavior."

echo
echo "=== Fix Status ==="
RAMP_DOWN_COUNT=$(grep -c "RAMP DOWN" test_output.log || echo "0")
FAILURE_RATE_COUNT=$(grep -c "getFailureRate()" test_output.log || echo "0")

if [ "$RAMP_DOWN_COUNT" -gt 0 ] && [ "$FAILURE_RATE_COUNT" -gt 0 ]; then
    echo "✅ SUCCESS: AdaptiveLoadPattern TPS adjustment appears to be working!"
    echo "   - Detected $RAMP_DOWN_COUNT ramp down events"
    echo "   - Detected $FAILURE_RATE_COUNT failure rate monitoring calls"
else
    echo "❌ ISSUE: AdaptiveLoadPattern TPS adjustment may still have problems"
    echo "   - Ramp down events: $RAMP_DOWN_COUNT"
    echo "   - Failure rate calls: $FAILURE_RATE_COUNT"
    echo "   - Review test_output.log for detailed analysis"
fi