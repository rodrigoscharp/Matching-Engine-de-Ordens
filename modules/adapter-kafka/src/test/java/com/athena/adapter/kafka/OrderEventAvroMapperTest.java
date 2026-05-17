package com.athena.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.athena.adapter.kafka.mapper.OrderEventAvroMapper;
import com.athena.trading.avro.OrderCancelledEvent;
import com.athena.trading.avro.OrderPlacedEvent;
import com.athena.trading.avro.TradeExecutedEvent;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.TradeId;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Avro mapper. No Kafka broker or Spring context needed — just domain objects
 * and the generated Avro classes.
 */
class OrderEventAvroMapperTest {

  private final OrderEventAvroMapper mapper = new OrderEventAvroMapper();

  private static final Symbol PETR4 = Symbol.of("PETR4");
  private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

  @Test
  void should_map_order_placed_limit_to_avro() {
    var orderId = OrderId.generate();
    var event =
        new OrderPlaced(
            orderId, PETR4, OrderSide.BUY, OrderType.LIMIT,
            Optional.of(Price.of(1050)), Quantity.of(100),
            42L, NOW, "key-123", NOW);

    var avro = (OrderPlacedEvent) mapper.toAvro(event);

    assertThat(avro.getOrderId()).isEqualTo(orderId.value().toString());
    assertThat(avro.getSymbol()).isEqualTo("PETR4");
    assertThat(avro.getSide()).isEqualTo("BUY");
    assertThat(avro.getType()).isEqualTo("LIMIT");
    assertThat(avro.getLimitPriceTicks()).isEqualTo(1050L);
    assertThat(avro.getOriginalQuantityLots()).isEqualTo(100L);
    assertThat(avro.getSequence()).isEqualTo(42L);
    assertThat(avro.getPlacedAt()).isEqualTo(NOW); // Avro truncates to millis
    assertThat(avro.getIdempotencyKey()).isEqualTo("key-123");
  }

  @Test
  void should_map_market_order_with_null_price_to_avro() {
    var event =
        new OrderPlaced(
            OrderId.generate(), PETR4, OrderSide.SELL, OrderType.MARKET,
            Optional.empty(), Quantity.of(50),
            1L, NOW, "mkt-key", NOW);

    var avro = (OrderPlacedEvent) mapper.toAvro(event);

    assertThat(avro.getType()).isEqualTo("MARKET");
    assertThat(avro.getLimitPriceTicks()).isNull();
  }

  @Test
  void should_map_trade_executed_to_avro() {
    var buyId = OrderId.generate();
    var sellId = OrderId.generate();
    var tradeId = TradeId.generate();
    var event =
        new TradeExecuted(
            tradeId, PETR4, buyId, sellId,
            Price.of(1050), Quantity.of(50), NOW);

    var avro = (TradeExecutedEvent) mapper.toAvro(event);

    assertThat(avro.getTradeId()).isEqualTo(tradeId.value().toString());
    assertThat(avro.getBuyOrderId()).isEqualTo(buyId.value().toString());
    assertThat(avro.getSellOrderId()).isEqualTo(sellId.value().toString());
    assertThat(avro.getExecutionPriceTicks()).isEqualTo(1050L);
    assertThat(avro.getExecutionQuantityLots()).isEqualTo(50L);
    assertThat(avro.getOccurredAt()).isEqualTo(NOW);
  }

  @Test
  void should_map_order_cancelled_to_avro() {
    var orderId = OrderId.generate();
    var event = new OrderCancelled(orderId, PETR4, Quantity.of(75), NOW);

    var avro = (OrderCancelledEvent) mapper.toAvro(event);

    assertThat(avro.getOrderId()).isEqualTo(orderId.value().toString());
    assertThat(avro.getSymbol()).isEqualTo("PETR4");
    assertThat(avro.getCancelledQuantityLots()).isEqualTo(75L);
    assertThat(avro.getOccurredAt()).isEqualTo(NOW);
  }
}
