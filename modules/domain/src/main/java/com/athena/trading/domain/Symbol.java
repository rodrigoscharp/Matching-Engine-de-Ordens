package com.athena.trading.domain;

import java.util.Objects;

/**
 * Instrument identifier (e.g., "PETR4", "BTC-USD"). Case-insensitive storage — always uppercased
 * on construction so equality checks are unambiguous.
 */
public record Symbol(String value) {

  public Symbol {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) throw new IllegalArgumentException("Symbol cannot be blank");
    value = value.toUpperCase();
  }

  public static Symbol of(String value) {
    return new Symbol(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
