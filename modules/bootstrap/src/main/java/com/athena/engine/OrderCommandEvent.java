package com.athena.engine;

import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import java.util.concurrent.CompletableFuture;

/**
 * Pre-allocated, mutable ring buffer entry for the LMAX Disruptor matching pipeline.
 *
 * <p>Disruptor pre-allocates a fixed pool of these objects (ring buffer size). Each event is
 * reused; fields must be cleared after processing to prevent leaking stale references.
 *
 * <p>NOT thread-safe by design — the Disruptor single-writer principle guarantees that only the
 * matching thread reads/writes the content after publishing.
 */
public final class OrderCommandEvent {

  public enum Type {
    PLACE_ORDER,
    CANCEL_ORDER
  }

  // Discriminator — always set before publish
  Type type;

  // PLACE_ORDER fields
  PlaceOrderCommand placeCommand;
  CompletableFuture<String> placeResult; // completes with orderId (UUID string)

  // CANCEL_ORDER fields
  CancelOrderCommand cancelCommand;
  CompletableFuture<Boolean> cancelResult;

  /** Called by the handler after processing to prevent GC-retained references. */
  void clear() {
    type = null;
    placeCommand = null;
    placeResult = null;
    cancelCommand = null;
    cancelResult = null;
  }
}
