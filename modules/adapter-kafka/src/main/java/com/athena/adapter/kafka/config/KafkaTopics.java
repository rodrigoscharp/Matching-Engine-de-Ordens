package com.athena.adapter.kafka.config;

/** Canonical topic names for the Trading bounded context. Partitioned by symbol. */
public final class KafkaTopics {

  public static final String ORDER_PLACED = "trading.orders.placed";
  public static final String TRADE_EXECUTED = "trading.orders.trades";
  public static final String ORDER_CANCELLED = "trading.orders.cancelled";

  private KafkaTopics() {}
}
