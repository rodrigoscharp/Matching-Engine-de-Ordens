<div align="center">

<img src="docs/logo.svg" alt="Athena" width="96" />

<br/><br/>

# ATHENA

**High-performance multi-asset order matching engine**

*Java 21 · Spring Boot 3.3 · Hexagonal Architecture · Event Sourcing · LMAX Disruptor*

<br/>

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?style=flat-square&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis&logoColor=white)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-6366F1?style=flat-square)](LICENSE)

<br/>

[![Architecture](https://img.shields.io/badge/Architecture-Hexagonal%20%2F%20Ports%20%26%20Adapters-818CF8?style=flat-square)](docs/adr/ADR-001-hexagonal-architecture.md)
[![Concurrency](https://img.shields.io/badge/Concurrency-LMAX%20Disruptor%20%2B%20Virtual%20Threads-818CF8?style=flat-square)](docs/adr/ADR-003-disruptor-virtual-threads.md)
[![Testing](https://img.shields.io/badge/Tests-ArchUnit%20%7C%20Testcontainers%20%7C%20Gatling-818CF8?style=flat-square)]()

<br/>

[Architecture](#architecture) · [Getting Started](#getting-started) · [Performance](#performance-targets) · [ADRs](#architecture-decision-records) · [Dashboard](#endpoints)

</div>

---

A high-performance, multi-asset order matching engine built with Java 21 and Spring Boot 3.3.
Designed to demonstrate production-grade engineering practices relevant to fintech backend roles:
hexagonal architecture, event sourcing, LMAX Disruptor concurrency, and full observability stack.

---

## What This Project Does

Athena accepts limit and market orders via REST, gRPC, or WebSocket; matches them using
price-time priority; publishes trade executions to Kafka; and provides a real-time order book
via a live trading dashboard.

```
Client → REST / gRPC / WebSocket
             ↓
        [Athena Engine]
             │
             ├─→ PostgreSQL   (event store — source of truth)
             ├─→ Redis        (idempotency keys, hot snapshots)
             └─→ Kafka        (trade execution stream)
```

The domain is intentionally decoupled from every framework. The matching logic in `modules/domain`
has zero Spring, zero Kafka, zero JDBC — it is plain Java 21. Hexagonal boundary violations
break the build via ArchUnit.

---

## Architecture

### Hexagonal Architecture (Ports & Adapters)

```
adapter-rest  ┐
adapter-grpc  ├──→ port/inbound  →  application  →  domain
adapter-ws    ┘         ↑                  ↓
                   port/outbound ←── adapter-persistence
                                ←── adapter-kafka
                                ←── adapter-redis
```

The application layer knows only about Java interfaces (ports). Adapters implement those
interfaces. The domain knows nothing outside `java.*`. See [ADR-001](docs/adr/ADR-001-hexagonal-architecture.md).

### LMAX Disruptor — Single-Writer Principle

All matching runs on **one thread**. HTTP/gRPC threads publish commands to the Disruptor ring
buffer; the single `MatchingEventHandler` dequeues them and owns every `OrderBook` exclusively.
Zero locks in the hot path. I/O (Kafka, PostgreSQL) dispatches to virtual threads off the
critical path.

```
[REST thread]  ┐
[gRPC thread]  ├──→ RingBuffer (4096) ──→ MatchingEventHandler (1 thread)
[WS thread]    ┘                                    │
                                       ┌────────────┴────────────┐
                                  Kafka publish           PostgreSQL write
                               (virtual thread)          (virtual thread)
```

See [ADR-003](docs/adr/ADR-003-disruptor-virtual-threads.md).

### Event Sourcing + CQRS

Every state change is persisted as an immutable event before it takes effect. The `OrderBook`
reconstructs its state by replaying events on startup. The read side (book snapshots, trade
history) is served from Redis projections, not from live book state.

```sql
order_events(id, symbol, sequence, event_type, payload JSONB, occurred_at, idempotency_key)
```

See [ADR-002](docs/adr/ADR-002-event-sourcing-cqrs.md).

### Price Representation (ADR-006)

Prices are stored as `long` ticks and quantities as `long` lots inside the domain. `BigDecimal`
only appears at REST/gRPC boundaries. This eliminates floating-point rounding in the critical
path and makes the matching logic trivially testable.

See [ADR-006](docs/adr/ADR-006-long-ticks-bigdecimal.md).

---

## Module Structure

```
modules/
├── domain/              # Pure Java — OrderBook, Order, matching logic, domain events
├── application/         # Use cases, inbound/outbound port interfaces
├── adapter-rest/        # Spring MVC controllers, DTOs, Swagger/OpenAPI
├── adapter-grpc/        # gRPC service (PlaceOrder, CancelOrder, StreamBookUpdates)
├── adapter-ws/          # WebSocket STOMP push (book snapshots every 250ms)
├── adapter-persistence/ # Spring Data JDBC + Flyway, PostgreSQL event store
├── adapter-kafka/       # Avro producers (Confluent Schema Registry)
├── adapter-redis/       # Lettuce client — idempotency store + book snapshot cache
├── observability/       # Micrometer, OpenTelemetry, structured logging (Logback)
├── bootstrap/           # @SpringBootApplication, Disruptor wiring, static dashboard
└── tests-architecture/  # ArchUnit rules — 14 hexagonal boundary checks
```

---

## Technology Stack

| Layer | Technology | Why |
|---|---|---|
| Language | Java 21 | Virtual Threads (Loom), Records, Sealed Interfaces, Pattern Matching |
| Framework | Spring Boot 3.3 | Auto-configuration, Actuator, dependency injection — kept out of domain |
| Concurrency | LMAX Disruptor 4 | Lock-free ring buffer; single-writer eliminates synchronization on matching |
| Concurrency | Virtual Threads (JEP 444) | High-throughput I/O without reactive complexity |
| Persistence | PostgreSQL 16 + Spring Data JDBC | Append-only event store; JDBC chosen over JPA to avoid lazy-load surprises |
| Migrations | Flyway 10 | Schema evolution in version control alongside code |
| Messaging | Apache Kafka 3.7 | At-least-once delivery of trade executions to downstream consumers |
| Schema | Confluent Avro + Schema Registry | Backwards-compatible schema evolution with compile-time contracts |
| Cache | Redis 7 (Lettuce) | Sub-millisecond idempotency checks and hot book snapshots |
| REST | Spring MVC + OpenAPI 3 | Swagger UI at `/swagger-ui.html` |
| gRPC | grpc-java 1.65 + Spring gRPC | Server-streaming for real-time book updates |
| WebSocket | Spring WebSocket + STOMP | Push book snapshots to browser dashboard every 250ms |
| Resilience | Resilience4j | Circuit breaker on external dependencies |
| Observability | Micrometer + OpenTelemetry | Metrics → Prometheus, Traces → Grafana Tempo |
| Logging | Logback + Logstash encoder | Structured JSON logs → Grafana Loki |
| Dashboards | Grafana | Pre-provisioned dashboards: JVM, Kafka lag, matching throughput |
| Testing | JUnit 5, AssertJ | Unit + integration tests; no Mockito for final/concrete classes |
| Property tests | jqwik | Randomised invariant verification of OrderBook |
| Architecture | ArchUnit | Hexagonal boundary violations break `mvn verify` |
| Integration | Testcontainers | PostgreSQL 16 + Redis 7 — never H2 or in-memory substitutes |
| Load testing | Gatling | REST scenarios: smoke / steady-state 50 VU / ramp to 200 VU |
| Benchmarks | JMH | Throughput and latency of the matching hot path |
| Build | Maven 3.9 (multi-module) | Consistent build lifecycle; JIB for Docker image production |
| Container | Google JIB | Reproducible Docker images without a Dockerfile |
| Formatting | Spotless + google-java-format | Enforced on every push via GitHub Actions |

---

## Key Engineering Practices

### Idempotency

Every write operation requires an `Idempotency-Key` UUID header. Duplicate requests return the
original result without re-processing. Keys are stored in Redis with a 24-hour TTL.

### No Lombok

Records cover immutable value objects. Builders and factory methods (`Order.limitBuy(...)`) cover
construction. Keeping the code standard Java reduces onboarding friction and IDE configuration
requirements.

### No `@Transactional` in the Domain or Application Layer

`@Transactional` lives exclusively on the persistence adapter. The application layer is
framework-agnostic and testable without a Spring context.

### Structured Logging

Every log statement uses key-value pairs (`kv("orderId", id), kv("price", price)`). String
concatenation is prohibited. TraceId and SpanId propagate via MDC.

### No H2 / Embedded Databases

Integration tests use real PostgreSQL and Redis via Testcontainers. Mock-based tests that passed
while real tests would have failed are a class of defect this project explicitly avoids.

### ArchUnit — Compile-Time Boundary Enforcement

14 rules check that:
- Domain classes import nothing outside `java.*` and the domain package
- Application layer imports nothing from adapters
- Controllers do not reach into the domain directly (must go through use cases)
- `@Transactional` is not used in the domain or application layer

A violation fails `mvn verify` and blocks the CI pipeline.

---

## Performance Targets

Measured by JMH (micro-benchmarks) and Gatling (end-to-end load tests):

| Metric | Target |
|---|---|
| Matching throughput per symbol | > 100,000 orders/s |
| Matching latency p50 | < 10 µs |
| Matching latency p99 | < 100 µs |
| REST submit p99 (steady 50 VU) | < 5 ms |
| gRPC submit p99 | < 2 ms |
| REST error rate at peak | < 0.1% |

Run benchmarks: `make bench`  
Run load test (requires running instance): `make load`

---

## Getting Started

### Prerequisites

- Java 21+
- Docker + Docker Compose
- GNU Make

### Run Locally

```bash
# 1. Start all infrastructure (PostgreSQL, Redis, Kafka, Grafana stack)
make infra-up

# 2. Compile and install all modules
./mvnw install -DskipTests

# 3. Start the application
make run
```

### Endpoints

| Endpoint | URL |
|---|---|
| Trading Dashboard | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Prometheus metrics | http://localhost:8080/actuator/prometheus |
| gRPC | localhost:9091 |
| Grafana | http://localhost:3000 (admin / admin) |

### Place an Order (curl)

```bash
# Limit buy: 100 units of PETR4 at R$ 38.50
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"symbol":"PETR4","side":"BUY","type":"LIMIT","price":38.50,"quantity":100}'

# Market sell: 50 units of PETR4
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"symbol":"PETR4","side":"SELL","type":"MARKET","quantity":50}'

# Get book snapshot
curl http://localhost:8080/api/v1/books/PETR4

# Cancel an order
curl -X DELETE http://localhost:8080/api/v1/orders/{orderId} \
  -H "Idempotency-Key: $(uuidgen)"
```

### Run Tests

```bash
# Unit tests only (fast, no Docker needed)
./mvnw test

# Unit + integration tests (requires Docker for Testcontainers)
make verify

# Architecture tests only
./mvnw test -pl modules/tests-architecture

# JMH benchmarks
make bench
```

---

## IntelliJ IDEA Setup

1. Open the project root as a Maven project.
2. Run `./mvnw generate-sources compile -DskipTests` once to generate Avro and Protobuf sources.
3. In IntelliJ: **Maven → Reload All Maven Projects**.
4. Mark generated source roots if IntelliJ does not pick them up automatically:
   - `modules/adapter-kafka/target/generated-sources/avro`
   - `modules/adapter-grpc/target/generated-sources/protobuf/java`
5. Use JDK 21 (not 22+) for local builds to keep Spotless formatting checks consistent with CI.

---

## Observability

Start the full stack with `make infra-up`. Pre-provisioned Grafana dashboards at
`http://localhost:3000`:

| Dashboard | What it shows |
|---|---|
| JVM / Spring Boot | Heap, GC, thread pool, HTTP latency histograms |
| Matching Engine | Ring buffer fill, orders/s, trade rate, p99 matching latency |
| Kafka | Consumer lag, producer throughput, topic partition health |
| Distributed Traces | End-to-end request trace from HTTP → Disruptor → Kafka |

Logs are shipped to Loki in JSON format and queryable via Grafana Explore.

---

## Architecture Decision Records

| ADR | Decision |
|---|---|
| [ADR-001](docs/adr/ADR-001-hexagonal-architecture.md) | Hexagonal Architecture (Ports & Adapters) |
| [ADR-002](docs/adr/ADR-002-event-sourcing-cqrs.md) | Event Sourcing + CQRS |
| [ADR-003](docs/adr/ADR-003-disruptor-virtual-threads.md) | LMAX Disruptor + Virtual Threads |
| [ADR-006](docs/adr/ADR-006-long-ticks-bigdecimal.md) | `long` ticks in domain, `BigDecimal` at REST/gRPC boundary |
| [ADR-007](docs/adr/ADR-007-observability-strategy.md) | Observability strategy (Micrometer, OTel, Loki) |

---

## Makefile Targets

```
make infra-up     Start all Docker services (DB, Redis, Kafka, Grafana)
make infra-down   Stop all Docker services
make run          Start the Spring Boot application
make verify       Full build + unit tests + integration tests
make bench        Run JMH benchmarks (domain module)
make load         Run Gatling load test (requires running instance)
make fmt          Apply code formatting (Spotless)
make lint         Check formatting without applying changes
```

---

## License

[MIT](LICENSE)
