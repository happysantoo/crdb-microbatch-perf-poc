# CRDB Microbatch Performance POC

This project demonstrates the effectiveness of CockroachDB's microbatching feature through load testing using VajraPulse.

## Overview

The project performs load testing on CockroachDB by inserting single rows into a non-trivial table (15+ columns) using VajraPulse. The load test ramps from 10 to 300 threads and continues until 1 million rows are inserted.

## Architecture

- **CockroachDB**: Single-node cluster for testing
- **Spring Boot 3.5.8**: Application framework
- **VajraPulse 0.9.4+**: Load testing framework using virtual threads
- **Prometheus**: Metrics collection (scrapes every 10 seconds)
- **Grafana**: Visualization dashboard

## Prerequisites

- Java 21
- Gradle 9.2.1+
- Docker and Docker Compose

## Setup VajraPulse OpenTelemetry Exporter

The `vajrapulse-exporter-otel` package may not be available in Maven Central. If you encounter build errors, you need to build it from source:

```bash
# Clone VajraPulse repository
git clone https://github.com/happysantoo/vajrapulse.git
cd vajrapulse

# Build and install the exporter to local Maven repository
./gradlew :vajrapulse-exporter-otel:publishToMavenLocal

# Then return to this project and build
cd /path/to/crdb-microbatch-perf-poc
./gradlew build
```

Alternatively, if the exporter is available in a snapshot repository, uncomment the repository configuration in `build.gradle.kts`.

## Setup

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This will start:
- CockroachDB on port 26257 (SQL) and 8080 (Admin UI)
- Prometheus on port 9090
- Grafana on port 3000 (admin/admin)

### 2. Initialize Database

The database will be automatically initialized by Flyway migrations when the application starts.

### 3. Build and Run

```bash
./gradlew build
./gradlew bootRun
```

The application will:
1. Connect to CockroachDB
2. Run database migrations
3. Start the load test (ramps from 10 to 300 threads)
4. Continue until 1 million rows are inserted
5. Expose metrics on `/actuator/prometheus`

## Monitoring

### Prometheus
- URL: http://localhost:9090
- Scrapes Spring Boot metrics every 10 seconds

### Grafana
- URL: http://localhost:3000
- Username: `admin`
- Password: `admin`
- Dashboard: "CRDB Microbatch Performance Test"

The dashboard includes:
- Throughput (inserts/sec)
- Latency percentiles (p50, p95, p99)
- Total rows inserted
- Insert errors
- JVM memory usage
- JVM threads (including virtual threads)
- CockroachDB node status
- GC pause times
- Insert duration distribution

### CockroachDB Admin UI
- URL: http://localhost:8080

## Load Test Configuration

- **Initial threads**: 10
- **Target threads**: 300
- **Ramp duration**: 5 minutes
- **Sustain duration**: Until 1M rows inserted
- **Target rows**: 1,000,000

## Metrics

The application exposes the following custom metrics:

- `crdb.inserts.total`: Total number of insert attempts
- `crdb.inserts.errors`: Total number of insert errors
- `crdb.inserts.duration`: Insert operation duration (histogram)
- `crdb.rows.inserted`: Current number of rows inserted (gauge)

Standard JVM metrics are also exposed via Spring Boot Actuator.

## Database Schema

The test table (`test_inserts`) includes:
- UUID primary key
- 15+ columns of various types (VARCHAR, TEXT, INTEGER, BIGINT, DECIMAL, BOOLEAN, JSONB, ARRAY, UUID, DATE, TIME, DOUBLE, FLOAT, TIMESTAMP)
- Indexes on timestamp, varchar, and integer columns

## Stopping

```bash
# Stop the application (Ctrl+C)
# Stop infrastructure
docker-compose down

# To remove volumes (clean slate)
docker-compose down -v
```

