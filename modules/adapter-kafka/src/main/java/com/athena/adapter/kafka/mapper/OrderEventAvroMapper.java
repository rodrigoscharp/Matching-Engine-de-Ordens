package com.athena.adapter.kafka.mapper;

import com.athena.trading.avro.OrderCancelledEvent;
import com.athena.trading.avro.OrderPlacedEvent;
import com.athena.trading.avro.TradeExecutedEvent;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/**
 * Converts domain events to Avro {@link SpecificRecord} objects for Kafka publishing.
 *
 * <p>Prices and quantities remain in ticks/lots (ADR-006). Timestamps are stored as epoch-millis
 * per the {@code timestamp-millis} logical type in the Avro schemas.
 */
@Component
public class OrderEventAvroMapper {

  public SpecificRecord toAvro(OrderPlaced event) {
    return OrderPlacedEvent.newBuilder()
        .setOrderId(event.orderId().value().toString())
        .setSymbol(event.symbol().value())
        .setSide(event.side().name())
        .setType(event.type().name())
        .setLimitPriceTicks(event.limitPrice().map(p -> p.ticks()).orElse(null))
        .setOriginalQuantityLots(event.originalQuantity().lots())
        .setSequence(event.sequence())
        .setPlacedAt(event.placedAt())       // Avro generates Instant for timestamp-millis
        .setIdempotencyKey(event.idempotencyKey())
        .setOccurredAt(event.occurredAt())
        .build();
  }

  public SpecificRecord toAvro(TradeExecuted event) {
    return TradeExecutedEvent.newBuilder()
        .setTradeId(event.tradeId().value().toString())
        .setSymbol(event.symbol().value())
        .setBuyOrderId(event.buyOrderId().value().toString())
        .setSellOrderId(event.sellOrderId().value().toString())
        .setExecutionPriceTicks(event.executionPrice().ticks())
        .setExecutionQuantityLots(event.executionQuantity().lots())
        .setOccurredAt(event.occurredAt())
        .build();
  }

  public SpecificRecord toAvro(OrderCancelled event) {
    return OrderCancelledEvent.newBuilder()
        .setOrderId(event.orderId().value().toString())
        .setSymbol(event.symbol().value())
        .setCancelledQuantityLots(event.cancelledQuantity().lots())
        .setOccurredAt(event.occurredAt())
        .build();
  }
}
