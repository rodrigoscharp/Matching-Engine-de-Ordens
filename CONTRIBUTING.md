# Contributing

## IDE Setup (IntelliJ IDEA)

1. Open the project root as a Maven project (File → Open → select `pom.xml`).
2. Run `./mvnw generate-sources compile -DskipTests` once to generate Avro and Protobuf sources.
3. **Maven → Reload All Maven Projects** to let IntelliJ pick up generated source roots.
4. If red code persists in `adapter-kafka` or `adapter-grpc`, right-click the target directories
   and mark them as "Generated Sources Root":
   - `modules/adapter-kafka/target/generated-sources/avro`
   - `modules/adapter-grpc/target/generated-sources/protobuf/java`
5. Use **JDK 21** for local builds. JDK 22+ skips the Spotless formatting step (see Maven profile
   `skip-spotless-jdk22plus`) and will diverge from CI output.

---

## Conventional Commits

All commits are written in English, in the form `type(scope): description`.

Types: `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `perf`, `ci`.

Examples:
```
feat(trading): add price-time priority matching for limit orders
fix(persistence): correct sequence gap detection on replay
test(domain): add property-based tests for OrderBook invariants
chore(infra): upgrade Kafka to 3.7.1
```

## Java Conventions

**No Lombok.** Records cover 90% of value-object cases. For controlled mutability, use manual
builders or static factory methods.

**Records for value objects:**
```java
record OrderId(UUID value) {
    OrderId { Objects.requireNonNull(value, "value"); }
    static OrderId generate() { return new OrderId(UUID.randomUUID()); }
}
```

**Sealed interfaces for closed hierarchies:**
```java
sealed interface OrderEvent permits OrderPlaced, OrderMatched, OrderCancelled {}
record OrderPlaced(OrderId orderId, ...) implements OrderEvent {}
```

**Use `var` sparingly:** use it when the type is obvious from context (`var order = Order.of(...)`),
avoid it when it obscures intent.

**No wildcard imports.** Configure your IDE not to collapse imports into `com.athena.*`.

**No field `@Autowired`.** Always use constructor injection:
```java
// Wrong
@Autowired private OrderRepository repository;

// Right
private final OrderRepository repository;
OrderService(OrderRepository repository) { this.repository = repository; }
```

**No `Optional` as a method parameter.** Use overloading or an explicit null check.

**No `double`/`float` for monetary values.** Use `long` in the domain (ticks/lots) and
`BigDecimal` at the boundary. See ADR-006.

**No string concatenation in logs:**
```java
// Wrong
log.info("Order " + orderId + " matched at " + price);

// Right
log.info("Order matched", kv("orderId", orderId), kv("price", price));
```

## Hexagonal Package Structure

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
            inbound/    # Interfaces the outside world calls
            outbound/   # Interfaces the application needs from the outside world
        service/        # Application services (orchestration)
    adapter/
        rest/           # @RestController (never business logic)
        grpc/           # gRPC service implementations
        ws/             # WebSocket handlers
        persistence/    # Spring Data JDBC repositories
        kafka/          # Kafka producers/consumers
        redis/          # Redis operations
```

## Tests

**Naming:** `should_<expected_behavior>_when_<condition>`.

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

**Minimum coverage:** 90% lines, 80% branches, 60% methods in the `domain` and `application`
modules. Enforced by JaCoCo during `make verify`.

**Mutation testing:** `domain` and `application` must keep a mutation score above 75% (PIT). Run
it with `mvn pitest:mutationCoverage`.

**No `Thread.sleep` in tests.** Use `Awaitility`:
```java
await().atMost(5, SECONDS).until(() -> consumer.received().size() >= 1);
```

**No H2 or embedded databases.** All integration tests use Testcontainers with real
Postgres/Redis/Kafka.

## Definition of Done (DoD)

A task is done when:
- [ ] `make verify` passes without warnings
- [ ] New code paths have unit tests (AAA, correct naming)
- [ ] No ArchUnit rule is violated
- [ ] Every public operation emits a metric, a trace, and a structured log
- [ ] `Idempotency-Key` is implemented for every state-changing write
- [ ] No `double`/`float` in monetary values
- [ ] No Spring in the `domain` module
- [ ] The PR describes the decision, not just what changed

## When to Write a New ADR

- A significant dependency change (new framework, new database)
- An architectural decision that is not obvious or that has strong alternatives
- Any change that breaks an established convention, along with its justification

Copy `docs/adr/_TEMPLATE.md`, fill in every field, and reference the ADR in the PR.
