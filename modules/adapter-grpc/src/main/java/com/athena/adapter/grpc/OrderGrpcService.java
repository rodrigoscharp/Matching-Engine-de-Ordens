package com.athena.adapter.grpc;

import com.athena.adapter.grpc.proto.BookSnapshot;
import com.athena.adapter.grpc.proto.BookStreamRequest;
import com.athena.adapter.grpc.proto.CancelOrderRequest;
import com.athena.adapter.grpc.proto.CancelOrderResponse;
import com.athena.adapter.grpc.proto.OrderServiceGrpc;
import com.athena.adapter.grpc.proto.PlaceOrderRequest;
import com.athena.adapter.grpc.proto.PlaceOrderResponse;
import com.athena.adapter.grpc.proto.PriceLevel;
import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC adapter implementing {@link OrderServiceGrpc.OrderServiceImplBase}.
 *
 * <ul>
 *   <li>{@code PlaceOrder} — unary RPC, maps to {@link PlaceOrderUseCase}
 *   <li>{@code CancelOrder} — unary RPC, maps to {@link CancelOrderUseCase}
 *   <li>{@code StreamBookUpdates} — server-streaming RPC; the {@link BookStreamingRegistry}
 *       pushes snapshots every ~250ms via {@link #pushToSubscribers}
 * </ul>
 *
 * <p>All prices/quantities remain in ticks/lots throughout — no BigDecimal at the gRPC boundary
 * (the proto schema already uses int64; ADR-006).
 */
@GrpcService
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

  private static final Logger log = LoggerFactory.getLogger(OrderGrpcService.class);

  private final PlaceOrderUseCase placeOrderUseCase;
  private final CancelOrderUseCase cancelOrderUseCase;
  private final GetBookSnapshotUseCase getBookSnapshotUseCase;

  // symbol → active streaming observers
  private final ConcurrentHashMap<String, List<ServerCallStreamObserver<BookSnapshot>>>
      streamSubscribers = new ConcurrentHashMap<>();

  public OrderGrpcService(
      PlaceOrderUseCase placeOrderUseCase,
      CancelOrderUseCase cancelOrderUseCase,
      GetBookSnapshotUseCase getBookSnapshotUseCase) {
    this.placeOrderUseCase = Objects.requireNonNull(placeOrderUseCase);
    this.cancelOrderUseCase = Objects.requireNonNull(cancelOrderUseCase);
    this.getBookSnapshotUseCase = Objects.requireNonNull(getBookSnapshotUseCase);
  }

  // ── PlaceOrder ───────────────────────────────────────────────────────────────

  @Override
  public void placeOrder(PlaceOrderRequest req, StreamObserver<PlaceOrderResponse> out) {
    try {
      var cmd =
          new PlaceOrderCommand(
              req.getIdempotencyKey(),
              req.getSymbol(),
              toOrderSide(req.getSide()),
              toOrderType(req.getType()),
              req.getLimitPriceTicks(),
              req.getQuantityLots(),
              Instant.now());

      String orderId = placeOrderUseCase.place(cmd);
      out.onNext(PlaceOrderResponse.newBuilder().setOrderId(orderId).build());
      out.onCompleted();
    } catch (IllegalArgumentException ex) {
      out.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    } catch (Exception ex) {
      log.error("gRPC PlaceOrder error", ex);
      out.onError(Status.INTERNAL.withDescription("Matching engine error").asRuntimeException());
    }
  }

  // ── CancelOrder ──────────────────────────────────────────────────────────────

  @Override
  public void cancelOrder(CancelOrderRequest req, StreamObserver<CancelOrderResponse> out) {
    try {
      var cmd = new CancelOrderCommand(req.getIdempotencyKey(), req.getOrderId());
      boolean cancelled = cancelOrderUseCase.cancel(cmd);
      out.onNext(
          CancelOrderResponse.newBuilder()
              .setOrderId(req.getOrderId())
              .setCancelled(cancelled)
              .build());
      out.onCompleted();
    } catch (Exception ex) {
      log.error("gRPC CancelOrder error", ex);
      out.onError(Status.INTERNAL.withDescription("Matching engine error").asRuntimeException());
    }
  }

  // ── StreamBookUpdates (server streaming) ────────────────────────────────────

  @Override
  public void streamBookUpdates(BookStreamRequest req, StreamObserver<BookSnapshot> out) {
    String symbol = req.getSymbol().toUpperCase();
    var serverObserver = (ServerCallStreamObserver<BookSnapshot>) out;

    List<ServerCallStreamObserver<BookSnapshot>> observers =
        streamSubscribers.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>());
    observers.add(serverObserver);

    serverObserver.setOnCancelHandler(
        () -> {
          observers.remove(serverObserver);
          log.debug("gRPC book stream cancelled for symbol {}", symbol);
        });

    log.debug("gRPC book stream started for symbol {}", symbol);

    // Send current snapshot immediately so the client doesn't wait up to 250ms
    getBookSnapshotUseCase
        .getSnapshot(symbol)
        .ifPresent(snap -> serverObserver.onNext(toProtoSnapshot(snap)));
  }

  /**
   * Called by the scheduled book streaming service every ~250ms. Pushes current snapshots to all
   * active gRPC subscribers.
   */
  public void pushToSubscribers(
      Map<String, com.athena.trading.domain.OrderBookSnapshot> snapshots) {
    streamSubscribers.forEach(
        (symbol, observers) -> {
          var snap = snapshots.get(symbol);
          if (snap == null) return;
          var proto = toProtoSnapshot(snap);
          observers.removeIf(
              obs -> {
                if (obs.isCancelled()) return true;
                try {
                  obs.onNext(proto);
                  return false;
                } catch (Exception ex) {
                  log.warn("Failed to push gRPC book update for {}", symbol, ex);
                  return true;
                }
              });
        });
  }

  // ── Mapping helpers ──────────────────────────────────────────────────────────

  private static BookSnapshot toProtoSnapshot(
      com.athena.trading.domain.OrderBookSnapshot snap) {
    var builder =
        BookSnapshot.newBuilder()
            .setSymbol(snap.symbol().value())
            .setTakenAtEpochMillis(snap.takenAt().toEpochMilli());

    snap.bids().forEach(
        level ->
            builder.addBids(
                PriceLevel.newBuilder()
                    .setPriceTicks(level.price().ticks())
                    .setTotalQuantityLots(level.totalQuantity().lots())
                    .setOrderCount(level.orderCount())
                    .build()));

    snap.asks().forEach(
        level ->
            builder.addAsks(
                PriceLevel.newBuilder()
                    .setPriceTicks(level.price().ticks())
                    .setTotalQuantityLots(level.totalQuantity().lots())
                    .setOrderCount(level.orderCount())
                    .build()));

    return builder.build();
  }

  private static OrderSide toOrderSide(com.athena.adapter.grpc.proto.Side side) {
    return switch (side) {
      case BUY -> OrderSide.BUY;
      case SELL -> OrderSide.SELL;
      default ->
          throw new IllegalArgumentException("Unknown side: " + side);
    };
  }

  private static OrderType toOrderType(com.athena.adapter.grpc.proto.Type type) {
    return switch (type) {
      case LIMIT -> OrderType.LIMIT;
      case MARKET -> OrderType.MARKET;
      default ->
          throw new IllegalArgumentException("Unknown type: " + type);
    };
  }
}
