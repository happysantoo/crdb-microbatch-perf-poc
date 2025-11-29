#!/bin/bash

# Script to verify metrics are being exported correctly

echo "=== Verifying Metrics Export ==="
echo ""

echo "1. Checking OpenTelemetry Collector Metrics Endpoint"
echo "   URL: http://localhost:8889/metrics"
echo ""

echo "   CRDB Batch Metrics:"
curl -s http://localhost:8889/metrics 2>/dev/null | grep -E "^crdb_batch" | head -10 || echo "   ⚠️  No CRDB batch metrics found"
echo ""

echo "   CRDB Submit Metrics:"
curl -s http://localhost:8889/metrics 2>/dev/null | grep -E "^crdb_submit" | head -10 || echo "   ⚠️  No CRDB submit metrics found"
echo ""

echo "   Vortex Metrics:"
curl -s http://localhost:8889/metrics 2>/dev/null | grep -E "^vortex" | head -10 || echo "   ⚠️  No Vortex metrics found"
echo ""

echo "   VajraPulse Metrics:"
curl -s http://localhost:8889/metrics 2>/dev/null | grep -E "^vajrapulse" | head -10 || echo "   ⚠️  No VajraPulse metrics found"
echo ""

echo "2. Checking Prometheus Targets"
echo "   URL: http://localhost:9090/targets"
echo "   (Check manually in browser)"
echo ""

echo "3. Key Metrics to Verify:"
echo ""
echo "   Total Rows Inserted:"
curl -s 'http://localhost:9090/api/v1/query?query=crdb_batch_rows_success_total' 2>/dev/null | jq -r '.data.result[] | "   \(.metric.__name__) = \(.value[1])"' || echo "   ⚠️  Metric not found"
echo ""

echo "   Rows Inserted/sec:"
curl -s 'http://localhost:9090/api/v1/query?query=rate(crdb_batch_rows_success_total[1m])' 2>/dev/null | jq -r '.data.result[] | "   \(.metric.__name__) = \(.value[1]) rows/sec"' || echo "   ⚠️  Metric not found"
echo ""

echo "4. All CRDB Metrics in Prometheus:"
curl -s 'http://localhost:9090/api/v1/label/__name__/values' 2>/dev/null | jq -r '.data[]' | grep -E "^crdb" | sort | head -20 || echo "   ⚠️  No CRDB metrics found"
echo ""

echo "=== Verification Complete ==="
echo ""
echo "If metrics are missing:"
echo "1. Ensure application is running: ./gradlew bootRun"
echo "2. Check OpenTelemetry collector is running"
echo "3. Verify Prometheus is scraping otel-collector"
echo "4. Check application logs for errors"
