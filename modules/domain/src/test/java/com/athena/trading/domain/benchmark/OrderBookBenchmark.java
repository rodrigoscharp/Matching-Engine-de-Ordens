package com.athena.trading.domain.benchmark;

import com.athena.trading.domain.Order;
import com.athena.trading.domain.OrderBook;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH micro-benchmarks for the order book matching engine.
 *
 * <p>Run via: {@code ./mvnw -pl modules/domain test -Pbenchmark} (see Makefile: {@code make bench})
 *
 * <p>Target performance (Sprint 5): &gt;100 000 orders/s per symbol, p99 &lt; 100 µs.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgs = {"-XX:+UseZGC", "-XX:+ZGenerational", "-Xmx512m"})
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
public class OrderBookBenchmark {

  private static final Symbol PETR4 = Symbol.of("PETR4");

  private OrderBook book;
  private long sequence;

  @Setup(Level.Invocation)
  public void setUp() {
    book = OrderBook.forSymbol(PETR4);
    sequence = 0;
  }

  /**
   * Baseline: place a single limit buy that does not match (rests in book). Measures raw order
   * insertion throughput.
   */
  @Benchmark
  public int placeNonMatchingLimitOrder() {
    book.place(limitBuy(100_00, 100));
    return book.totalRestingOrders();
  }

  /**
   * Hot path: place a limit sell that immediately matches a pre-existing limit buy. Measures
   * full matching cycle including event production.
   */
  @Benchmark
  public int placeMatchingPair() {
    book.place(limitBuy(100_00, 100));
    var events = book.place(limitSell(100_00, 100));
    return events.size(); // forces the JIT not to dead-code eliminate the result
  }

  /**
   * Sweep scenario: one large buy sweeps through N resting sell levels. Simulates a market impact
   * event — the most expensive path through the matching algorithm.
   */
  @Benchmark
  public int sweepFiveLevels() {
    // Pre-populate 5 sell levels (100 lots each)
    for (int i = 1; i <= 5; i++) {
      book.place(limitSell(100_00 + i, 100));
    }
    // Aggressive buy sweeps all five levels
    var events = book.place(limitBuy(100_05, 500));
    return events.size();
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  private Order limitBuy(long priceTicks, long lots) {
    return Order.limitBuy(
        OrderId.generate(), PETR4, Price.of(priceTicks), Quantity.of(lots),
        ++sequence, Instant.now(), "b-" + sequence);
  }

  private Order limitSell(long priceTicks, long lots) {
    return Order.limitSell(
        OrderId.generate(), PETR4, Price.of(priceTicks), Quantity.of(lots),
        ++sequence, Instant.now(), "s-" + sequence);
  }

  /**
   * Entry point for running benchmarks directly from an IDE. Use {@code make bench} for production
   * runs with proper forked JVM settings.
   */
  public static void main(String[] args) throws RunnerException {
    Options opts =
        new OptionsBuilder()
            .include(OrderBookBenchmark.class.getSimpleName())
            .forks(1)
            .build();
    new Runner(opts).run();
  }
}
