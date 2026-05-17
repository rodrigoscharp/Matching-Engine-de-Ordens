package com.athena.trading.application.port.inbound;

import com.athena.trading.domain.OrderBookSnapshot;
import java.util.Map;
import java.util.Optional;

/**
 * Primary port: read the current state of an order book. Part of the query side of CQRS. Returns
 * an empty snapshot (not an error) when no orders have been placed for the symbol yet.
 */
public interface GetBookSnapshotUseCase {

  /**
   * Returns the current book snapshot for the symbol.
   *
   * @return {@link Optional#empty()} only when no book has been initialized for the symbol; the
   *     caller should treat this as an empty book, not as an error.
   */
  Optional<OrderBookSnapshot> getSnapshot(String symbol);

  /**
   * Returns snapshots for all initialized symbols. Used by streaming adapters (WebSocket, gRPC)
   * to push updates to subscribed clients without knowing which symbols are active.
   *
   * @return unmodifiable map of symbol → snapshot; empty if no orders have been placed yet.
   */
  Map<String, OrderBookSnapshot> getAllSnapshots();
}
