# Athena

Motor de matching de ordens multi-asset em Java 21 + Spring Boot 3.3 — projeto de estudo e portfólio para posições backend em fintechs e bigtechs.

## Por que esse projeto

Quero entender como exchanges funcionam de dentro: como um order book é mantido eficientemente, como se garante que duas ordens casam exatamente uma vez e nenhuma outra vez, e o que separa um sistema financeiro de um CRUD bem feito. A Athena é o laboratório onde respondo essas perguntas com código real, não com slides.

## Estado atual

Em desenvolvimento ativo — sprints quinzenais. Sprint 1 (Foundation) em andamento. Veja o [Roadmap](#roadmap).

## Em uma frase técnica

Hexagonal Architecture + DDD tático + Event Sourcing + CQRS sobre LMAX Disruptor, Kafka e PostgreSQL, exposto via REST / gRPC / WebSocket, com observabilidade completa (Micrometer, OpenTelemetry, Prometheus, Grafana).

## Stack

| Camada | Tecnologia |
|--------|-----------|
| Runtime | Java 21, Spring Boot 3.3 |
| Concorrência | LMAX Disruptor 4, Virtual Threads |
| Persistência | PostgreSQL 16, Spring Data JDBC, Flyway 10 |
| Mensageria | Kafka 3.7, Confluent Schema Registry, Avro |
| APIs | REST (Spring MVC), gRPC 1.65, WebSocket |
| Cache | Redis 7 (Lettuce) |
| Observabilidade | Micrometer 1.13, OpenTelemetry, Prometheus, Grafana, Tempo, Loki |
| Testes | JUnit 5, Testcontainers, ArchUnit, jqwik, Pact, Gatling |
| Build | Maven 3.9, JIB (Docker sem Dockerfile) |

## Como rodar

Pré-requisito: Java 21+, Docker, Make.

```bash
# 1. Subir a infraestrutura local (Postgres, Redis, Kafka, Observabilidade)
make infra-up

# 2. Rodar migrations
make db-migrate

# 3. Subir a aplicação
make run
```

Endpoints disponíveis após o startup:

| Endpoint | URL |
|----------|-----|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Métricas (Prometheus) | http://localhost:8080/actuator/prometheus |
| gRPC | localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |

## Estrutura do repositório

```
athena/
├── modules/
│   ├── domain/             # Java puro — zero frameworks
│   ├── application/        # Casos de uso, portas (inbound/outbound)
│   ├── adapter-rest/       # Spring MVC controllers
│   ├── adapter-grpc/       # gRPC service implementations
│   ├── adapter-ws/         # WebSocket handlers
│   ├── adapter-persistence/# Spring Data JDBC repositories
│   ├── adapter-kafka/      # Producers e consumers Kafka
│   ├── adapter-redis/      # Cache e projeções Redis
│   ├── observability/      # Micrometer, OTel, logging config
│   ├── bootstrap/          # @SpringBootApplication — monta tudo
│   └── tests-architecture/ # ArchUnit — regras de dependência
├── docs/adr/               # Architecture Decision Records
├── ops/                    # Configurações Prometheus, Tempo, Loki, Grafana
├── scripts/                # smoke.sh, etc.
└── docker-compose.yml      # Stack local completa
```

## Performance

Targets medidos por JMH e Gatling (Sprint 5+):

| Métrica | Target |
|---------|--------|
| Throughput por símbolo | > 100.000 ordens/s |
| Latência p50 (matching) | < 10 µs |
| Latência p99 (matching) | < 100 µs |
| Latência p99 (REST submit) | < 5 ms |
| Latência p99 (gRPC submit) | < 2 ms |

## Decisões de design

| ADR | Decisão |
|-----|---------|
| [ADR-001](docs/adr/ADR-001-hexagonal-architecture.md) | Arquitetura Hexagonal (Ports & Adapters) |
| [ADR-002](docs/adr/ADR-002-event-sourcing-cqrs.md) | Event Sourcing + CQRS |
| [ADR-003](docs/adr/ADR-003-disruptor-virtual-threads.md) | LMAX Disruptor + Virtual Threads |
| [ADR-006](docs/adr/ADR-006-long-ticks-bigdecimal.md) | `long` ticks no core, `BigDecimal` na borda |

## Roadmap

| Sprint | Tema | Status |
|--------|------|--------|
| 1 — Foundation | Maven multi-módulo, Docker stack, ArchUnit, CI | Em andamento |
| 2 — Domain Core | OrderBook, MatchingService, OrderSide/Type, Event Sourcing | Planejado |
| 3 — REST + Persistence | Submit order, book snapshot, Flyway migrations | Planejado |
| 4 — Kafka + gRPC | Event publishing, Market Data stream, gRPC service | Planejado |
| 5 — Performance | Disruptor pipeline, JMH benchmarks, Gatling load test | Planejado |
| 6 — Observabilidade | Dashboards Grafana, alertas, SLO definitions | Planejado |

## Licença

[MIT](LICENSE)
