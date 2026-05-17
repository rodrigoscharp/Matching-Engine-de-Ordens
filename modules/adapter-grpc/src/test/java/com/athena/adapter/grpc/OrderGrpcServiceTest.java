package com.athena.adapter.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.athena.adapter.grpc.proto.CancelOrderRequest;
import com.athena.adapter.grpc.proto.PlaceOrderRequest;
import com.athena.adapter.grpc.proto.PlaceOrderResponse;
import com.athena.adapter.grpc.proto.Side;
import com.athena.adapter.grpc.proto.Type;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderGrpcServiceTest {

  @Mock private PlaceOrderUseCase placeOrderUseCase;
  @Mock private CancelOrderUseCase cancelOrderUseCase;
  @Mock private GetBookSnapshotUseCase getBookSnapshotUseCase;

  private OrderGrpcService service;

  @BeforeEach
  void setUp() {
    service = new OrderGrpcService(placeOrderUseCase, cancelOrderUseCase, getBookSnapshotUseCase);
  }

  @Test
  void should_place_limit_buy_and_return_order_id() {
    var orderId = "550e8400-e29b-41d4-a716-446655440000";
    when(placeOrderUseCase.place(any())).thenReturn(orderId);

    var request =
        PlaceOrderRequest.newBuilder()
            .setIdempotencyKey("key-1")
            .setSymbol("PETR4")
            .setSide(Side.BUY)
            .setType(Type.LIMIT)
            .setLimitPriceTicks(1050)
            .setQuantityLots(100)
            .build();

    var captured = new AtomicReference<PlaceOrderResponse>();
    StreamObserver<PlaceOrderResponse> observer =
        new StreamObserver<>() {
          @Override public void onNext(PlaceOrderResponse value) { captured.set(value); }
          @Override public void onError(Throwable t) { throw new AssertionError("unexpected error", t); }
          @Override public void onCompleted() {}
        };

    service.placeOrder(request, observer);

    assertThat(captured.get().getOrderId()).isEqualTo(orderId);
    verify(placeOrderUseCase).place(any());
  }

  @Test
  void should_cancel_order_and_return_result() {
    when(cancelOrderUseCase.cancel(any())).thenReturn(true);

    var request =
        CancelOrderRequest.newBuilder()
            .setIdempotencyKey("cancel-key")
            .setOrderId("some-uuid")
            .build();

    var cancelled = new AtomicReference<Boolean>();
    StreamObserver<com.athena.adapter.grpc.proto.CancelOrderResponse> observer =
        new StreamObserver<>() {
          @Override public void onNext(com.athena.adapter.grpc.proto.CancelOrderResponse value) {
            cancelled.set(value.getCancelled());
          }
          @Override public void onError(Throwable t) { throw new AssertionError("unexpected error", t); }
          @Override public void onCompleted() {}
        };

    service.cancelOrder(request, observer);

    assertThat(cancelled.get()).isTrue();
  }

  @Test
  void should_return_grpc_error_for_invalid_side() {
    var request =
        PlaceOrderRequest.newBuilder()
            .setIdempotencyKey("key-bad")
            .setSymbol("PETR4")
            .setSide(Side.SIDE_UNSPECIFIED)
            .setType(Type.LIMIT)
            .setLimitPriceTicks(1050)
            .setQuantityLots(100)
            .build();

    var errorRef = new AtomicReference<Throwable>();
    StreamObserver<PlaceOrderResponse> observer =
        new StreamObserver<>() {
          @Override public void onNext(PlaceOrderResponse value) {}
          @Override public void onError(Throwable t) { errorRef.set(t); }
          @Override public void onCompleted() {}
        };

    service.placeOrder(request, observer);

    assertThat(errorRef.get()).isNotNull();
    assertThat(errorRef.get().getMessage()).contains("INVALID_ARGUMENT");
  }

  @Test
  void should_push_to_grpc_subscribers_from_snapshot_map() {
    service.pushToSubscribers(Map.of()); // No subscribers registered — must not throw
  }
}
