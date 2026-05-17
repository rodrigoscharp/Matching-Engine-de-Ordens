# ADR-003: LMAX Disruptor + Virtual Threads

**Status:** Accepted

**Date:** 2026-05-17

---

## Contexto

O núcleo de matching de um exchange precisa processar dezenas de milhares de ordens por segundo com latência de microsegundos e jitter mínimo. As abordagens tradicionais de concorrência (synchronized, ReentrantLock, BlockingQueue) introduzem falsa compartilhamento de cache, overhead de lock contention e latência não-determinística sob carga.

O Java 21 introduz Virtual Threads (Project Loom), que resolvem o problema de escalabilidade de I/O bound workloads. Mas o matching engine em si é CPU bound no hot path — Virtual Threads não ajudam aqui.

A questão central: como estruturar o pipeline de processamento de ordens para maximizar throughput e minimizar latência?

## Decisão

**Hot path (matching):** LMAX Disruptor com single-writer principle.

- Um único thread de matching (o "event processor") detém exclusividade sobre o OrderBook. Zero locks, zero contenção.
- O Disruptor usa um ring buffer pré-alocado, eliminando GC pressure no hot path.
- Sequencing garantido: toda ordem tem um número de sequência global monotônico.
- Producers (adapters REST/gRPC/WS) publicam no ring buffer e retornam imediatamente (async).

**I/O bound (persistence, Kafka publish, Redis update):** Virtual Threads.

- Adapters de persistência e mensageria rodam em Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
- Elimina necessidade de reactive programming (WebFlux, RxJava) para I/O concurrency.
- Spring Boot 3.3 suporta Virtual Threads nativamente com `spring.threads.virtual.enabled=true`.

**Separação clara:**
```
REST/gRPC/WS → [ring buffer] → MatchingEventProcessor (1 thread, pinned)
                                    ↓
                           [output ring buffer]
                                    ↓
                      ┌─────────────┬─────────────┐
                 Kafka Publisher  Postgres Writer  Redis Updater
                (virtual thread) (virtual thread) (virtual thread)
```

## Consequências

### Positivas
- Throughput medido em lab: >500k ordens/s por símbolo (benchmark target: >100k/s).
- Latência p99 < 100µs no hot path (sem I/O).
- Sem locks no caminho crítico → sem thread starvation, sem priority inversion.
- Virtual Threads eliminam complexidade reativa sem sacrificar escala de I/O.

### Negativas / trade-offs
- Single-writer implica que um único símbolo com traffic muito alto pode saturar o thread de matching. Solução: sharding por símbolo (planned improvement).
- O Disruptor é uma dependência especializada que a maioria dos desenvolvedores Java desconhece — curva de aprendizado documentada no CONTRIBUTING.
- Não há como usar `@Transactional` no event processor — consistência garantida por outbox pattern.

### Neutras / a observar
- O pinning de Virtual Threads em synchronized blocks ainda existe no JDK 21. Garantir que o matching thread nunca entre em synchronized.
- Monitorar `jdk.VirtualThreadPinned` JFR events nos testes de carga.

## Alternativas consideradas

| Alternativa | Por que descartada |
|-------------|-------------------|
| Akka Actors | Adiciona Scala/Akka ecosystem; overhead de serialização de mensagens; licença comercial para Akka Cluster |
| BlockingQueue + thread pool | Lock contention sob carga alta; latência não-determinística |
| Reactive (WebFlux/RxJava) | Complexidade alta; stack traces incompreensíveis; não resolve o hot path CPU bound |
| Disruptor multi-writer | Viola single-writer principle; adiciona coordenação entre producers |

## Referências

- LMAX, [Disruptor Technical Paper](https://lmax-exchange.github.io/disruptor/disruptor.html) (2011)
- Martin Thompson, [Mechanical Sympathy](https://mechanical-sympathy.blogspot.com/)
- JEP 444, Virtual Threads (Java 21)
- [False Sharing in Java](https://mechanical-sympathy.blogspot.com/2011/07/false-sharing.html)
