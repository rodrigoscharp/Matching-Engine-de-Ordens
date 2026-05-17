package com.athena.trading.application.command;

import java.util.Objects;

/** Command to cancel a resting order. Idempotent — cancelling an already-cancelled order is safe. */
public record CancelOrderCommand(String idempotencyKey, String orderId) {

  public CancelOrderCommand {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey cannot be blank");
    Objects.requireNonNull(orderId, "orderId");
    if (orderId.isBlank()) throw new IllegalArgumentException("orderId cannot be blank");
  }
}
