# ADR-007: Observability Strategy — Micrometer + OpenTelemetry + Grafana Stack

**Status:** Accepted

**Date:** 2026-05-17

---

## Contexto

Um matching engine financeiro opera em sub-milissegundo para o hot path de matching, mas seus efeitos (trades, order book state) propagam para múltiplos sistemas downstream. Sem observabilidade adequada, falhas silenciosas (evento não persistido, Kafka lag, ring buffer cheio) passam despercebidas até gerarem inconsistências.

Os requisitos são:
1. Toda operação pública emite métrica, trace e log estruturado
2. Alertas automáticos para violações de SLO
3. Dashboards que mostrem o estado do sistema em tempo real
4. Correlação entre logs, métricas e traces (the "three pillars of observability")

## Decisão

**Métricas**: Micrometer com backend Prometheus. Nomes em dot-notation, tags de baixa cardinalidade (symbol, side, type). Histogramas com percentis pré-computados para latência. Recording rules no Prometheus para pré-agregar queries do dashboard.

**Tracing**: Micrometer Observation API (ponte OTel via `micrometer-tracing-bridge-otel`) exportando para Grafana Tempo. O `ObservationRegistry` envolve as operações `place()` e `cancel()` — cria spans distribuídos E timers de latência com uma única anotação.

**Logs**: logstash-logback-encoder (JSON estruturado). MDC propagado automaticamente pelo Micrometer Tracing: `traceId` e `spanId` aparecem em todo log de uma request. Campos adicionais: `symbol`, `orderId`, `side` via `MDC.put()` no `MatchingEventHandler`.

**Stack de visualização**: Prometheus + Grafana Tempo (traces) + Grafana Loki (logs) + Grafana 11. Correlação automática via `traceId`: um log com traceId tem link direto para o trace no Tempo; um trace tem link para os logs no Loki.

**Alertas**: Prometheus Alertmanager com SLOs definidos em `ops/prometheus/alerts.yml`:
- REST p99 > 5ms por 2 minutos → warning
- Error rate > 0.1% por 5 minutos → critical
- Ring buffer > 80% por 1 minuto → warning

**Health indicator**: `DisruptorHealthIndicator` expõe o estado do ring buffer via `/actuator/health/disruptor`. Integrado ao Kubernetes liveness/readiness probe em produção.

## Consequências

### Positivas
- Correlação logs/traces/métricas via `traceId` — causa raiz de um incidente em segundos, não horas
- `@Observation` + Micrometer substitui `@Timed` + `@NewSpan` — uma abstração para dois pilares
- Recording rules pré-computam queries caras — dashboards renderizam em <1s mesmo sob carga
- `DisruptorHealthIndicator` permite detectar backpressure antes de timeouts de cliente

### Negativas / trade-offs
- O matching thread recebe duas chamadas de `System.nanoTime()` por evento (para o timer). Custo: ~10-20ns — aceitável dado que o hot path é ~1µs
- `MDC.put()` no `MatchingEventHandler` adiciona ~5ns por evento — aceitável
- Histogramas com `publishPercentileHistogram(true)` geram mais séries Prometheus — monitorar cardinalidade

### Neutras / a observar
- Em produção, usar sampling 10% para traces (não 100%) — modificar `TRACING_SAMPLING_PROBABILITY` via env var
- Alertas com Alertmanager requerem configuração adicional de receivers (Slack, PagerDuty) — not yet implemented

## Alternativas consideradas

| Alternativa | Por que descartada |
|-------------|-------------------|
| Logs apenas (sem metrics + traces) | Não permite alertas automáticos; correlação manual é lenta |
| Jaeger em vez de Tempo | Grafana Tempo integra nativamente com Grafana; Jaeger requer stack separada |
| `@Timed` + `@NewSpan` (Micrometer 1.x) | Substituídos pela Observation API em Micrometer 1.10+; mais verboso |
| InfluxDB em vez de Prometheus | Prometheus é padrão de mercado; pull model é mais simples operacionalmente |

## Referências

- Micrometer Observation API: https://micrometer.io/docs/observation
- Grafana LGTM Stack: Loki + Grafana + Tempo + Mimir
- Google SRE Book, Cap. 4: Service Level Objectives
