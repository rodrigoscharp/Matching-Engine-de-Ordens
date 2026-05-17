package com.athena.adapter.persistence.mapper;

import com.athena.adapter.persistence.entity.OrderEventRecord;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.TradeId;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Converts between domain {@link OrderEvent} objects and {@link OrderEventRecord} persistence
 * entities. JSON payload serialization lives here — the domain has no Jackson dependency.
 */
@Component
public class OrderEventMapper {

  private static final String ORDER_PLACED = "ORDER_PLACED";
  private static final String TRADE_EXECUTED = "TRADE_EXECUTED";
  private static final String ORDER_CANCELLED = "ORDER_CANCELLED";

  private final ObjectMapper objectMapper;

  public OrderEventMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public OrderEventRecord toRecord(OrderEvent event) {
    return switch (event) {
      case OrderPlaced e -> toRecord(e);
      case TradeExecuted e -> toRecord(e);
      case OrderCancelled e -> toRecord(e);
    };
  }

  public OrderEvent toDomain(OrderEventRecord record) {
    return switch (record.eventType()) {
      case ORDER_PLACED -> toOrderPlaced(record);
      case TRADE_EXECUTED -> toTradeExecuted(record);
      case ORDER_CANCELLED -> toOrderCancelled(record);
      default ->
          throw new IllegalArgumentException("Unknown event type: " + record.eventType());
    };
  }

  // ── to record ─────────────────────────────────────────────────────────────────

  private OrderEventRecord toRecord(OrderPlaced e) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("side", e.side().name());
    payload.put("type", e.type().name());
    e.limitPrice().ifPresent(p -> payload.put("limitPriceTicks", p.ticks()));
    payload.put("originalQuantityLots", e.originalQuantity().lots());
    payload.put("placedAt", e.placedAt().toString());
    payload.put("idempotencyKey", e.idempotencyKey());
    return new OrderEventRecord(
        e.symbol().value(),
        e.orderId().value().toString(),
        e.sequence(),
        ORDER_PLACED,
        writeJson(payload),
        e.occurredAt());
  }

  private OrderEventRecord toRecord(TradeExecuted e) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tradeId", e.tradeId().value().toString());
    payload.put("buyOrderId", e.buyOrderId().value().toString());
    payload.put("sellOrderId", e.sellOrderId().value().toString());
    payload.put("executionPriceTicks", e.executionPrice().ticks());
    payload.put("executionQuantityLots", e.executionQuantity().lots());
    return new OrderEventRecord(
        e.symbol().value(),
        e.buyOrderId().value().toString(),
        null,
        TRADE_EXECUTED,
        writeJson(payload),
        e.occurredAt());
  }

  private OrderEventRecord toRecord(OrderCancelled e) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cancelledQuantityLots", e.cancelledQuantity().lots());
    return new OrderEventRecord(
        e.symbol().value(),
        e.orderId().value().toString(),
        null,
        ORDER_CANCELLED,
        writeJson(payload),
        e.occurredAt());
  }

  // ── to domain ─────────────────────────────────────────────────────────────────

  private OrderPlaced toOrderPlaced(OrderEventRecord r) {
    Map<String, Object> p = readJson(r.payload());
    var side = com.athena.trading.domain.OrderSide.valueOf((String) p.get("side"));
    var type = com.athena.trading.domain.OrderType.valueOf((String) p.get("type"));
    Optional<Price> limitPrice =
        p.containsKey("limitPriceTicks")
            ? Optional.of(Price.of(((Number) p.get("limitPriceTicks")).longValue()))
            : Optional.empty();
    var qty = Quantity.of(((Number) p.get("originalQuantityLots")).longValue());
    var seq = r.sequence() != null ? r.sequence() : 0L;
    var placedAt = Instant.parse((String) p.get("placedAt"));
    var idempotencyKey = (String) p.get("idempotencyKey");

    return new OrderPlaced(
        OrderId.of(r.orderId()),
        Symbol.of(r.symbol()),
        side,
        type,
        limitPrice,
        qty,
        seq,
        placedAt,
        idempotencyKey,
        r.occurredAt());
  }

  private TradeExecuted toTradeExecuted(OrderEventRecord r) {
    Map<String, Object> p = readJson(r.payload());
    return new TradeExecuted(
        new TradeId(UUID.fromString((String) p.get("tradeId"))),
        Symbol.of(r.symbol()),
        OrderId.of((String) p.get("buyOrderId")),
        OrderId.of((String) p.get("sellOrderId")),
        Price.of(((Number) p.get("executionPriceTicks")).longValue()),
        Quantity.of(((Number) p.get("executionQuantityLots")).longValue()),
        r.occurredAt());
  }

  private OrderCancelled toOrderCancelled(OrderEventRecord r) {
    Map<String, Object> p = readJson(r.payload());
    return new OrderCancelled(
        OrderId.of(r.orderId()),
        Symbol.of(r.symbol()),
        Quantity.of(((Number) p.get("cancelledQuantityLots")).longValue()),
        r.occurredAt());
  }

  // ── JSON helpers ──────────────────────────────────────────────────────────────

  private String writeJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize event payload", e);
    }
  }

  private Map<String, Object> readJson(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize event payload: " + json, e);
    }
  }
}
