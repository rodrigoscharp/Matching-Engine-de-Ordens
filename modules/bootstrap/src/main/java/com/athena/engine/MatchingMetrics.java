package com.athena.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Records business metrics for the matching engine.
 *
 * <p>All metric names use dot-notation (Micrometer convention). Prometheus auto-converts to
 * snake_case with type suffixes ({@code _total}, {@code _seconds_*}).
 *
 * <p>Tag cardinality is deliberately low: {@code symbol} (10-100 instruments), {@code side}
 * (BUY/SELL), {@code type} (LIMIT/MARKET). This prevents metric explosion.
 */
@Component
public class MatchingMetrics {

  // ── Metric names ──────────────────────────────────────────────────────────────
  static final String ORDERS_ACCEPTED = "athena.orders.accepted";
  static final String ORDERS_CANCELLED = "athena.orders.cancelled";
  static final String TRADES_EXECUTED = "athena.trades.executed";
  static final String TRADE_VOLUME_LOTS = "athena.trades.volume.lots";
  static final String MATCHING_DURATION = "athena.matching.book.duration";

  private final MeterRegistry registry;

  // Lazy-registered tagged counters — avoids creating meters for unused symbol/side/type combos
  private final ConcurrentHashMap<String, Counter> acceptedCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> cancelledCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> tradeCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, DistributionSummary> volumeSummaries =
      new ConcurrentHashMap<>();

  private final Timer matchingTimer;

  public MatchingMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.matchingTimer =
        Timer.builder(MATCHING_DURATION)
            .description("Time spent executing price-time priority matching (excluding I/O)")
            .publishPercentiles(0.5, 0.95, 0.99, 0.999)
            .publishPercentileHistogram(true)
            .register(registry);
  }

  // ── Recording methods ─────────────────────────────────────────────────────────

  public void recordOrderAccepted(String symbol, String side, String type) {
    acceptedCounters
        .computeIfAbsent(
            symbol + "|" + side + "|" + type,
            k ->
                Counter.builder(ORDERS_ACCEPTED)
                    .description("Total orders accepted by the matching engine")
                    .tag("symbol", symbol)
                    .tag("side", side)
                    .tag("type", type)
                    .register(registry))
        .increment();
  }

  public void recordOrderCancelled(String symbol) {
    cancelledCounters
        .computeIfAbsent(
            symbol,
            k ->
                Counter.builder(ORDERS_CANCELLED)
                    .description("Total orders cancelled")
                    .tag("symbol", symbol)
                    .register(registry))
        .increment();
  }

  public void recordTradeExecuted(String symbol, long quantityLots) {
    tradeCounters
        .computeIfAbsent(
            symbol,
            k ->
                Counter.builder(TRADES_EXECUTED)
                    .description("Total trades executed")
                    .tag("symbol", symbol)
                    .register(registry))
        .increment();

    volumeSummaries
        .computeIfAbsent(
            symbol,
            k ->
                DistributionSummary.builder(TRADE_VOLUME_LOTS)
                    .description("Distribution of trade volumes in lots")
                    .tag("symbol", symbol)
                    .register(registry))
        .record(quantityLots);
  }

  /** Records the wall-clock time of the matching algorithm (OrderBook.place only, not I/O). */
  public void recordMatchingDuration(long nanos) {
    matchingTimer.record(nanos, TimeUnit.NANOSECONDS);
  }
}
