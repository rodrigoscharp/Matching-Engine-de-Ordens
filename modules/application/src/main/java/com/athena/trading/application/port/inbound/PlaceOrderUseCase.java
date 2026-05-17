package com.athena.trading.application.port.inbound;

import com.athena.trading.application.command.PlaceOrderCommand;

/** Primary port: submit a new order to the matching engine. */
public interface PlaceOrderUseCase {

  /**
   * Places an order. Idempotent — repeating the same {@code idempotencyKey} returns the original
   * order ID string without re-processing.
   *
   * @return the assigned order identifier as a UUID string
   */
  String place(PlaceOrderCommand command);
}
