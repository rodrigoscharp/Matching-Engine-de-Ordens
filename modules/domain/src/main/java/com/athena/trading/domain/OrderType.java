package com.athena.trading.domain;

/** Execution semantics of an order. Only LIMIT and MARKET for now; IOC/FOK in a later sprint. */
public enum OrderType {
  LIMIT,
  MARKET;

  public boolean isLimit() {
    return this == LIMIT;
  }

  public boolean isMarket() {
    return this == MARKET;
  }
}
