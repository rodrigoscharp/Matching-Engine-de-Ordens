# ADR-002: Event Sourcing + CQRS

**Status:** Accepted

**Date:** 2026-05-17

---

## Contexto

Um matching engine é intrinsecamente orientado a eventos: cada ordem submetida, cada match, cada cancelamento é um evento com timestamp e sequência. O estado atual do book de ordens é derivado desses eventos.

Auditabilidade é obrigatória em sistemas financeiros: reguladores exigem a capacidade de replay de qualquer estado histórico. Guardar apenas o estado atual (CRUD) não satisfaz esse requisito sem log de auditoria separado — que acaba sendo Event Sourcing de qualquer forma.

CQRS é a consequência natural: leituras (book snapshot, candles, histórico) têm padrões de acesso completamente diferentes de escritas (submissão de ordens), e tentar satisfazer ambos com o mesmo modelo de dados leva a índices excessivos ou queries pesadas.

## Decisão

Adotamos Event Sourcing para o agregado `OrderBook`:

- Todo estado deriva de uma sequência ordenada e imutável de eventos de domínio (`OrderPlaced`, `OrderMatched`, `OrderCancelled`, `OrderAmended`).
- Eventos são persistidos no Postgres (tabela `order_events`) como a fonte da verdade.
- O estado atual do book em memória (hot path) é mantido pelo Disruptor e reconstruído via replay no startup.
- Eventos são publicados no Kafka para consumidores downstream (Market Data, Account).

CQRS:
- **Command side**: recebe ordens, valida, persiste eventos, retorna ID da ordem. Síncrono e garantido.
- **Query side**: lê de projeções Redis (book snapshot, last trade) e Postgres (histórico). Eventual consistency explícita e documentada.

Idempotência garantida por `Idempotency-Key` em todo command: o mesmo key retorna o mesmo resultado sem processar duas vezes.

## Consequências

### Positivas
- Auditabilidade nativa: qualquer estado passado é reproduzível por replay.
- Desacoplamento total entre escrita e leitura — modelos optimizados para cada caso.
- Testabilidade: dado uma lista de eventos, o estado esperado é determinístico.
- Escalabilidade de leitura independente da escrita.

### Negativas / trade-offs
- Eventual consistency nas queries: leitores podem ver estado ligeiramente desatualizado. Documentado explicitamente na API (headers `X-Book-Sequence`).
- Mais complexidade inicial: dois modelos, projeções, event store.
- Queries ad-hoc em estado atual requerem projeções prévias — não é possível fazer `SELECT * WHERE price > X` no estado corrente sem materializar.

### Neutras / a observar
- Snapshots periódicos do book serão necessários quando o log de eventos crescer (planned improvement).
- Compensating events (vs. soft deletes) para cancelamentos — mesma complexidade.

## Alternativas consideradas

| Alternativa | Por que descartada |
|-------------|-------------------|
| CRUD com log de auditoria separado | O log é ES de forma incompleta; duplica esforço |
| CQRS sem Event Sourcing | Perde auditabilidade e a capacidade de replay |
| Event Sourcing em Kafka apenas (sem Postgres) | Kafka não é banco de dados; retenção, replayabilidade e consistência transacional são problemáticas |

## Referências

- Martin Fowler, [Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
- Greg Young, CQRS Documents (2010)
- Vaughn Vernon, *Implementing Domain-Driven Design*, Cap. 4–7
