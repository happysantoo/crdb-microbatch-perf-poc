# Grafana Dashboard Reload Instructions

## Issue: Panel Not Showing After Update

If you've updated a dashboard JSON file but don't see the changes in Grafana, follow these steps:

## Solution 1: Force Grafana to Reload Dashboards

### Option A: Restart Grafana Container
```bash
docker restart crdb-microbatch-grafana
```

### Option B: Reload via Grafana API
```bash
# Get API key from Grafana UI (Configuration > API Keys)
# Then reload dashboards:
curl -X POST http://admin:admin@localhost:3000/api/admin/provisioning/dashboards/reload
```

### Option C: Wait for Auto-Reload
Grafana is configured to auto-reload dashboards every 10 seconds. Wait 10-15 seconds and refresh the browser.

## Solution 2: Verify Dashboard is Loaded

1. **Check Grafana UI:**
   - Go to http://localhost:3000
   - Navigate to Dashboards > Browse
   - Look for "CRDB Microbatch Performance Dashboard"
   - Open the dashboard

2. **Check Panel Title:**
   - The panel title was updated from "Vortex Queue Metrics" to **"Vortex Queue & Backpressure Metrics"**
   - Look for the new title in the dashboard

3. **Check Panel Position:**
   - The panel is at position: `x: 0, y: 20` (left column, row 20)
   - Scroll down in the dashboard to see it

## Solution 3: Re-import Dashboard

If the panel still doesn't appear:

1. **Export Current Dashboard:**
   - Open dashboard in Grafana
   - Click gear icon (Settings)
   - Click "JSON Model"
   - Copy the JSON

2. **Compare with File:**
   - Check if the panel exists in the JSON
   - Look for `"title": "Vortex Queue & Backpressure Metrics"`

3. **Re-import:**
   - In Grafana: Dashboards > Import
   - Paste the JSON from the file
   - Click "Load"
   - Click "Import"

## Solution 4: Check Docker Volume Mount

Verify the dashboard file is mounted correctly:

```bash
# Check if file exists in container
docker exec crdb-microbatch-grafana ls -la /var/lib/grafana/dashboards/

# Check file content
docker exec crdb-microbatch-grafana cat /var/lib/grafana/dashboards/crdb-microbatch-dashboard-complete.json | grep "Vortex Queue"
```

## Solution 5: Check Grafana Logs

Check for errors in Grafana logs:

```bash
docker logs crdb-microbatch-grafana | grep -i "dashboard\|error" | tail -20
```

## Expected Panel Metrics

The "Vortex Queue & Backpressure Metrics" panel should show:

1. **Queue Depth (Current)** - Current items in queue
2. **Max Queue Size (1000)** - Reference line
3. **Backpressure Level (0.0-1.0)** - Calculated backpressure
4. **Reject Threshold (0.7)** - Red reference line at 70%
5. **Rejected/sec (Backpressure >= 0.7)** - Rejection rate
6. **Queue Wait Time/sec** - Average wait time
7. **Queue Wait p50** - 50th percentile wait time
8. **Queue Wait p95** - 95th percentile wait time

## Quick Fix Command

```bash
# Restart Grafana to force reload
docker restart crdb-microbatch-grafana

# Wait 10 seconds for Grafana to start
sleep 10

# Verify dashboard is accessible
curl -s http://localhost:3000/api/health | jq .
```

## Troubleshooting

### Panel exists but shows "No data"
- Check if metrics are being exported: `curl http://localhost:8889/metrics | grep vortex`
- Verify Prometheus is scraping: Check Prometheus targets at http://localhost:9090/targets
- Check metric names match exactly (case-sensitive)

### Panel doesn't exist at all
- Verify JSON is valid: `python3 -m json.tool grafana/dashboards/crdb-microbatch-dashboard-complete.json`
- Check panel ID is unique (no duplicate IDs)
- Verify panel is in the `panels` array

### Dashboard not showing in Grafana
- Check provisioning config: `cat grafana/provisioning/dashboards/dashboard.yml`
- Verify volume mount in docker-compose.yml
- Check Grafana logs for errors

