# Port 8888 Conflict Resolution

## Issue
The OpenTelemetry collector was failing to start with error:
```
Error: cannot start pipelines: failed to start "prometheus" exporter: listen tcp 0.0.0.0:8888: bind: address already in use
```

## Investigation
- No process was found listening on port 8888 on the host (`lsof -iTCP:8888`)
- No Docker containers were actively using port 8888
- This appeared to be a Docker networking issue, possibly from a previous container that wasn't fully cleaned up

## Solution
Changed the Prometheus exporter port from **8888** to **8889** in:
1. `otel-collector/otel-collector-config.yaml` - Changed endpoint from `0.0.0.0:8888` to `0.0.0.0:8889`
2. `docker-compose.yml` - Changed port mapping from `8888:8888` to `8889:8889`
3. `prometheus/prometheus.yml` - Updated scrape target from `otel-collector:8888` to `otel-collector:8889`

## Current Status
✅ OpenTelemetry collector is now running successfully on port 8889
✅ Metrics endpoint: http://localhost:8889/metrics
✅ Prometheus is configured to scrape from the collector

## Access Points
- **OpenTelemetry Collector Metrics**: http://localhost:8889/metrics
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000

