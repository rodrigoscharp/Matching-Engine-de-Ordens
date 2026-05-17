package com.athena.trading.application.port.inbound;

import com.athena.trading.application.command.CancelOrderCommand;

/** Primary port: cancel a resting order. */
public interface CancelOrderUseCase {

  /**
   * Cancels the order. No-op if the order is already in a terminal state or does not exist.
   * Idempotent — repeating the same {@code idempotencyKey} with the same order id is safe.
   *
   * @return {@code true} if the order was cancelled, {@code false} if already terminal or missing
   */
  boolean cancel(CancelOrderCommand command);
}
