package com.athena.trading.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Point-in-time read model of an order book. Used by the query side (CQRS) and for Market Data.
 * Carries no mutable state — safe to share across threads.
 */
public record OrderBookSnapshot(
    Symbol symbol, List<PriceLevel> bids, List<PriceLevel> asks, Instant takenAt) {

  public record PriceLevel(Price price, Quantity totalQuantity, int orderCount) {
    public PriceLevel {
      Objects.requireNonNull(price, "price");
      Objects.requireNonNull(totalQuantity, "totalQuantity");
      if (orderCount <= 0) throw new IllegalArgumentException("orderCount must be positive");
    }
  }

  public OrderBookSnapshot {
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(takenAt, "takenAt");
    bids = List.copyOf(Objects.requireNonNull(bids, "bids"));
    asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
  }

  public boolean hasBids() {
    return !bids.isEmpty();
  }

  public boolean hasAsks() {
    return !asks.isEmpty();
  }

  public static OrderBookSnapshot empty(Symbol symbol) {
    return new OrderBookSnapshot(symbol, List.of(), List.of(), Instant.now());
  }
}
