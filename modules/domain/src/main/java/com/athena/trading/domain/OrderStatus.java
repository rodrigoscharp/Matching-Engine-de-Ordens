package com.athena.trading.domain;

/** Lifecycle state of an order. Terminal states: FILLED, CANCELLED, REJECTED. */
public enum OrderStatus {
  OPEN,
  PARTIALLY_FILLED,
  FILLED,
  CANCELLED,
  REJECTED;

  public boolean isTerminal() {
    return this == FILLED || this == CANCELLED || this == REJECTED;
  }

  public boolean isActive() {
    return this == OPEN || this == PARTIALLY_FILLED;
  }
}
