package com.athena.adapter.ws.dto;

import com.athena.trading.domain.OrderBookSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * WebSocket message payload pushed to {@code /topic/books/{symbol}}.
 *
 * <p>Prices are converted from ticks to BigDecimal (2dp) at this adapter boundary — ADR-006. The
 * tick multiplier is 100 (1 BRL = 100 ticks); instrument-specific tick sizes are not yet implemented.
 */
public record BookUpdateMessage(
    String symbol, List<Level> bids, List<Level> asks, Instant takenAt) {

  public record Level(BigDecimal price, BigDecimal totalQuantity, int orderCount) {}

  private static final BigDecimal TICK_DIVISOR = BigDecimal.valueOf(100);

  public static BookUpdateMessage from(OrderBookSnapshot snap) {
    return new BookUpdateMessage(
        snap.symbol().value(),
        snap.bids().stream().map(BookUpdateMessage::toLevel).toList(),
        snap.asks().stream().map(BookUpdateMessage::toLevel).toList(),
        snap.takenAt());
  }

  private static Level toLevel(OrderBookSnapshot.PriceLevel level) {
    return new Level(
        BigDecimal.valueOf(level.price().ticks()).divide(TICK_DIVISOR, 2, RoundingMode.UNNECESSARY),
        BigDecimal.valueOf(level.totalQuantity().lots()),
        level.orderCount());
  }
}
