package com.athena.adapter.rest.dto;

import com.athena.trading.domain.OrderBookSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * REST representation of an order book snapshot. Prices and quantities are converted from
 * ticks/lots back to human-readable BigDecimal at this boundary (ADR-006).
 *
 * <p>Conversion uses a fixed tick multiplier of 100 (2 decimal places). Instrument-specific
 * tick/lot sizes are not yet implemented.
 */
public record BookSnapshotResponse(
    String symbol, List<PriceLevelResponse> bids, List<PriceLevelResponse> asks, Instant takenAt) {

  public record PriceLevelResponse(
      BigDecimal price, BigDecimal totalQuantity, int orderCount) {}

  private static final BigDecimal TICK_DIVISOR = BigDecimal.valueOf(100);

  public static BookSnapshotResponse from(OrderBookSnapshot snapshot) {
    return new BookSnapshotResponse(
        snapshot.symbol().value(),
        snapshot.bids().stream().map(BookSnapshotResponse::toLevel).toList(),
        snapshot.asks().stream().map(BookSnapshotResponse::toLevel).toList(),
        snapshot.takenAt());
  }

  public static BookSnapshotResponse empty(String symbol) {
    return new BookSnapshotResponse(symbol, List.of(), List.of(), Instant.now());
  }

  private static PriceLevelResponse toLevel(OrderBookSnapshot.PriceLevel level) {
    return new PriceLevelResponse(
        BigDecimal.valueOf(level.price().ticks()).divide(TICK_DIVISOR, 2, RoundingMode.UNNECESSARY),
        BigDecimal.valueOf(level.totalQuantity().lots()),
        level.orderCount());
  }
}
