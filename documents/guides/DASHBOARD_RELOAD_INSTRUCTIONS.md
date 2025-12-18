# Dashboard Reload Instructions

## Issue: Not Seeing Panel Changes

If you've updated the dashboard JSON but don't see changes in Grafana:

## Quick Fix Steps

### 1. Hard Refresh Browser
- **Mac**: `Cmd + Shift + R`
- **Windows/Linux**: `Ctrl + Shift + R`
- This clears browser cache

### 2. Verify Panel Location
The "Submit vs Insert Accounting" panel is at:
- **Row**: 36 (scroll down)
- **Column**: 0 (left side)
- **Title**: "Submit vs Insert Accounting"

### 3. Force Dashboard Reload

**Option A: Restart Grafana Container**
```bash
docker restart crdb-microbatch-grafana
```

**Option B: Reload via API**
```bash
curl -X POST -u admin:admin 'http://localhost:3000/api/admin/provisioning/dashboards/reload'
```

**Option C: Wait for Auto-Reload**
- Grafana auto-reloads dashboards every 10 seconds
- Wait 10-15 seconds and refresh browser

### 4. Re-import Dashboard (Last Resort)

If the panel still doesn't appear:

1. **Export Current Dashboard**:
   - Open dashboard in Grafana
   - Click gear icon (⚙️ Settings)
   - Click "JSON Model"
   - Copy the JSON

2. **Compare with File**:
   - Check if panel exists: Look for `"title": "Submit vs Insert Accounting"`
   - If missing, the dashboard needs to be re-imported

3. **Re-import**:
   - In Grafana: Dashboards → Import
   - Upload `grafana/dashboards/crdb-microbatch-dashboard-complete.json`
   - Select "Prometheus" datasource
   - Click "Import"

## Verify Panel is Loaded

Check if the panel exists in the dashboard:

```bash
# Check file in container
docker exec crdb-microbatch-grafana grep "Submit vs Insert Accounting" /var/lib/grafana/dashboards/crdb-microbatch-dashboard-complete.json

# Should output: "title": "Submit vs Insert Accounting"
```

## Panel Details

**Panel Name**: "Submit vs Insert Accounting"
**Panel ID**: 28
**Position**: x: 0, y: 36
**Type**: Timeseries (stacked bars)

**Metrics Shown**:
- Successfully Inserted (`crdb_batch_rows_success_total`)
- Failed Rows (`crdb_batch_rows_failure_total`)
- Rejected (Backpressure) - Vortex (`vortex_backpressure_rejected_total`) ← **This is the key metric!**
- Rejected (Backpressure) - App Detected (`crdb_submits_rejected_backpressure_total`)
- Rejected (Other) (`crdb_submits_rejected_other_total`)
- In Queue (`vortex_queue_depth`)
- In-Flight (`crdb_submits_success_total - crdb_batch_rows_total_total`)
- Unaccounted (Should be ~0)
- Total Vortex Requests (`vortex_requests_submitted_total`)

## Expected Behavior

After reload, you should see:
- The "Rejected (Backpressure) - Vortex" line showing ~5.5M (the missing records)
- The "Total Vortex Requests" line showing ~18M
- The accounting should now balance: 18M = 12.5M inserted + 5.5M rejected + others

