package com.athena.adapter.persistence.entity;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC aggregate root for the {@code order_events} table. Uses explicit queries — no
 * JPA, no Hibernate, no lazy loading surprises.
 *
 * <p>{@code id} is null on creation; Spring Data JDBC populates it after insert.
 */
@Table("order_events")
public record OrderEventRecord(
    @Id Long id,
    @Column("symbol") String symbol,
    @Column("order_id") String orderId,
    @Column("sequence") Long sequence,
    @Column("event_type") String eventType,
    @Column("payload") String payload,
    @Column("occurred_at") Instant occurredAt) {

  /** Constructor used when creating new records (id is null — assigned by the database). */
  public OrderEventRecord(
      String symbol,
      String orderId,
      Long sequence,
      String eventType,
      String payload,
      Instant occurredAt) {
    this(null, symbol, orderId, sequence, eventType, payload, occurredAt);
  }
}
