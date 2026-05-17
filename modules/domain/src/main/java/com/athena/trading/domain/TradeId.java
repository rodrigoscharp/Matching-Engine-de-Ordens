package com.athena.trading.domain;

import java.util.Objects;
import java.util.UUID;

/** Globally unique identifier for an executed trade. Immutable value object. */
public record TradeId(UUID value) {

  public TradeId {
    Objects.requireNonNull(value, "value");
  }

  public static TradeId generate() {
    return new TradeId(UUID.randomUUID());
  }

  public static TradeId of(String uuidString) {
    return new TradeId(UUID.fromString(uuidString));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
