# Contributing

## Conventional Commits

Todos os commits em inglês, formato `type(scope): description`.

Tipos: `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `perf`, `ci`.

Exemplos:
```
feat(trading): add price-time priority matching for limit orders
fix(persistence): correct sequence gap detection on replay
test(domain): add property-based tests for OrderBook invariants
chore(infra): upgrade Kafka to 3.7.1
```

## Padrões Java

**Sem Lombok.** Records cobrem 90% dos casos de valor. Para mutabilidade controlada, use builders manuais ou factory methods estáticos.

**Records para objetos de valor:**
```java
record OrderId(UUID value) {
    OrderId { Objects.requireNonNull(value, "value"); }
    static OrderId generate() { return new OrderId(UUID.randomUUID()); }
}
```

**Sealed interfaces para hierarquias fechadas:**
```java
sealed interface OrderEvent permits OrderPlaced, OrderMatched, OrderCancelled {}
record OrderPlaced(OrderId orderId, ...) implements OrderEvent {}
```

**`var` com moderação:** use quando o tipo é óbvio pelo contexto (`var order = Order.of(...)`), evite quando obscurece a intenção.

**Sem wildcard imports.** Configure seu IDE para não usar `import com.athena.*`.

**Sem `@Autowired` em campo.** Sempre injeção por construtor:
```java
// Errado
@Autowired private OrderRepository repository;

// Certo
private final OrderRepository repository;
OrderService(OrderRepository repository) { this.repository = repository; }
```

**Sem `Optional` como parâmetro de método.** Use overloading ou null check explícito.

**Sem `double`/`float` em valores monetários.** Use `long` no domínio (ticks/lots), `BigDecimal` na borda. Ver ADR-006.

**Sem log com concatenação:**
```java
// Errado
log.info("Order " + orderId + " matched at " + price);

// Certo
log.info("Order matched", kv("orderId", orderId), kv("price", price));
```

## Estrutura de pacote hexagonal

```
com.athena.{context}/
    domain/
        model/          # Entities, Value Objects, Aggregates
        event/          # Domain events
        service/        # Domain services (stateless logic)
    application/
        command/        # Command objects (inbound DTOs)
        query/          # Query objects
        port/
            inbound/    # Interfaces que o mundo chama
            outbound/   # Interfaces que o app precisa do mundo
        service/        # Application services (orchestration)
    adapter/
        rest/           # @RestController (nunca lógica de negócio)
        grpc/           # gRPC service implementations
        ws/             # WebSocket handlers
        persistence/    # Spring Data JDBC repositories
        kafka/          # Kafka producers/consumers
        redis/          # Redis operations
```

## Testes

**Nomenclatura:** `should_<expected_behavior>_when_<condition>`.

```java
@Test
void should_match_buy_with_sell_when_prices_cross() { ... }

@Test
void should_reject_order_when_quantity_is_zero() { ... }
```

**AAA (Arrange-Act-Assert):**
```java
// Arrange
var book = OrderBook.empty(Symbol.of("PETR4"));
var buy  = Order.limitBuy(price(10_00), qty(100));
var sell = Order.limitSell(price(10_00), qty(100));

// Act
var trades = book.place(sell);

// Assert
assertThat(trades).hasSize(1);
assertThat(trades.get(0).quantity()).isEqualTo(qty(100));
```

**Cobertura mínima:** 90% linhas, 80% branches, 60% métodos nos módulos `domain` e `application`. Enforçado pelo JaCoCo no `make verify`.

**Mutation testing:** `domain` e `application` devem ter mutation score > 75% (PIT). Rodar com `mvn pitest:mutationCoverage`.

**Sem `Thread.sleep` em testes.** Use `Awaitility`:
```java
await().atMost(5, SECONDS).until(() -> consumer.received().size() >= 1);
```

**Sem H2 ou embedded databases.** Todos os testes de integração usam Testcontainers com Postgres/Redis/Kafka reais.

## Definition of Done (DoD)

Uma tarefa está pronta quando:
- [ ] `make verify` passa sem warnings
- [ ] Novos caminhos têm testes unitários (AAA, nomenclatura correta)
- [ ] Nenhuma regra ArchUnit violada
- [ ] Toda operação pública emite métrica, trace e log estruturado
- [ ] `Idempotency-Key` implementado para toda escrita de estado
- [ ] Sem `double`/`float` em valores monetários
- [ ] Sem Spring no módulo `domain`
- [ ] PR descreve a decisão (não apenas o que mudou)

## Quando criar um novo ADR

- Mudança de dependência significativa (novo framework, novo banco)
- Decisão de arquitetura que não é óbvia ou que tem alternativas fortes
- Qualquer mudança que quebre a convenção estabelecida com justificativa

Copie `docs/adr/_TEMPLATE.md`, preencha todos os campos, e mencione o ADR no PR.
