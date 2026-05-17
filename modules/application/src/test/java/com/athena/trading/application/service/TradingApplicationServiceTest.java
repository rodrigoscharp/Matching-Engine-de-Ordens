package com.athena.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.OrderBook;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.event.OrderPlaced;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TradingApplicationServiceTest {

  @Mock private OrderEventStore eventStore;
  @Mock private DomainEventPublisher eventPublisher;
  @Mock private IdempotencyStore idempotencyStore;

  private TradingApplicationService service;

  @BeforeEach
  void setUp() {
    service = new TradingApplicationService(eventStore, eventPublisher, idempotencyStore);
  }

  @Test
  void should_place_limit_buy_and_return_order_id() {
    when(idempotencyStore.find("key-1")).thenReturn(Optional.empty());

    var cmd = limitBuyCommand("key-1", "PETR4", 100, 50);
    var orderId = service.place(cmd);

    assertThat(orderId).isNotNull();
    verify(eventStore).append(argThat(events ->
        events.stream().anyMatch(e -> e instanceof OrderPlaced)));
    verify(eventPublisher).publish(any());
    verify(idempotencyStore).store("key-1", orderId);
  }

  @Test
  void should_return_cached_order_id_when_idempotency_key_already_processed() {
    var existingId = OrderId.generate();
    when(idempotencyStore.find("key-dup")).thenReturn(Optional.of(existingId));

    var cmd = limitBuyCommand("key-dup", "PETR4", 100, 50);
    var result = service.place(cmd);

    assertThat(result).isEqualTo(existingId);
    verify(eventStore, never()).append(any());
    verify(eventPublisher, never()).publish(any());
  }

  @Test
  void should_cancel_resting_order() {
    when(idempotencyStore.find(any())).thenReturn(Optional.empty());
    var orderId = service.place(limitBuyCommand("place-key", "VALE3", 50, 100));

    var cancelled = service.cancel(new CancelOrderCommand("cancel-key", orderId.toString()));

    assertThat(cancelled).isTrue();
    // eventStore.append is called twice: once for place, once for cancel
    verify(eventStore, atLeastOnce()).append(
        argThat(events -> events.stream().anyMatch(
            e -> e instanceof com.athena.trading.domain.event.OrderCancelled)));
  }

  @Test
  void should_return_false_when_cancelling_non_existent_order() {
    when(idempotencyStore.find(any())).thenReturn(Optional.empty());

    var cancelled = service.cancel(
        new CancelOrderCommand("key", OrderId.generate().toString()));

    assertThat(cancelled).isFalse();
    verify(eventStore, never()).append(any());
  }

  @Test
  void should_produce_trades_when_matching_buy_and_sell() {
    when(idempotencyStore.find(any())).thenReturn(Optional.empty());

    service.place(limitSellCommand("sell-key", "ITUB4", 100, 200));
    service.place(limitBuyCommand("buy-key", "ITUB4", 100, 200));

    // The second place call produces OrderPlaced + TradeExecuted events
    verify(eventStore, atLeastOnce()).append(
        argThat(events -> events.stream().anyMatch(
            e -> e instanceof com.athena.trading.domain.event.TradeExecuted)));
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  private static PlaceOrderCommand limitBuyCommand(
      String key, String symbol, long priceTicks, long lots) {
    return new PlaceOrderCommand(key, symbol, OrderSide.BUY, OrderType.LIMIT,
        priceTicks, lots, Instant.now());
  }

  private static PlaceOrderCommand limitSellCommand(
      String key, String symbol, long priceTicks, long lots) {
    return new PlaceOrderCommand(key, symbol, OrderSide.SELL, OrderType.LIMIT,
        priceTicks, lots, Instant.now());
  }
}
