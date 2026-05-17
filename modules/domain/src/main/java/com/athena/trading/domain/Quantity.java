package com.athena.trading.domain;

/**
 * Quantity expressed in integer lots (smallest tradable unit for an instrument).
 *
 * <p>Uses {@code long} for the same reasons as {@link Price} — integer arithmetic is exact and
 * allocation-free in the hot path. See ADR-006.
 */
public record Quantity(long lots) implements Comparable<Quantity> {

  public Quantity {
    if (lots <= 0) {
      throw new IllegalArgumentException("Quantity lots must be positive, got: " + lots);
    }
  }

  public static Quantity of(long lots) {
    return new Quantity(lots);
  }

  public Quantity subtract(Quantity other) {
    if (other.lots > this.lots) {
      throw new IllegalArgumentException(
          "Cannot subtract " + other.lots + " from " + this.lots + " lots");
    }
    return new Quantity(this.lots - other.lots);
  }

  public Quantity add(Quantity other) {
    return new Quantity(Math.addExact(this.lots, other.lots));
  }

  public boolean isGreaterThan(Quantity other) {
    return this.lots > other.lots;
  }

  public boolean isLessThan(Quantity other) {
    return this.lots < other.lots;
  }

  @Override
  public int compareTo(Quantity other) {
    return Long.compare(this.lots, other.lots);
  }

  @Override
  public String toString() {
    return lots + " lots";
  }
}
