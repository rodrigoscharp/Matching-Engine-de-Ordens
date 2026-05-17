package com.athena.trading.domain;

/**
 * Price expressed in integer ticks (smallest price increment for an instrument).
 *
 * <p>Using {@code long} instead of {@code double} or {@code BigDecimal} avoids floating-point
 * rounding errors in the hot matching path. See ADR-006.
 *
 * <p>Conversion to/from human-readable decimal happens only at the adapter boundary.
 */
public record Price(long ticks) implements Comparable<Price> {

  public Price {
    if (ticks <= 0) {
      throw new IllegalArgumentException("Price ticks must be positive, got: " + ticks);
    }
  }

  public static Price of(long ticks) {
    return new Price(ticks);
  }

  /** Returns true if a BUY at this price crosses (can match against) a resting SELL at other. */
  public boolean crossesBuy(Price restingSell) {
    return this.ticks >= restingSell.ticks;
  }

  /** Returns true if a SELL at this price crosses (can match against) a resting BUY at other. */
  public boolean crossesSell(Price restingBuy) {
    return this.ticks <= restingBuy.ticks;
  }

  @Override
  public int compareTo(Price other) {
    return Long.compare(this.ticks, other.ticks);
  }

  @Override
  public String toString() {
    return ticks + " ticks";
  }
}
