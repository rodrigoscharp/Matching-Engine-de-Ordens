package com.athena.adapter.persistence.entity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC aggregate root for the {@code order_events} table. Uses explicit queries — no
 * JPA, no Hibernate, no lazy loading surprises.
 *
 * <p>Field types mirror the column types exactly: {@code order_id} is {@code uuid} and {@code
 * payload} is {@code jsonb}, and PostgreSQL will not coerce a {@code varchar} parameter into
 * either. Declaring them as {@link String} compiles and then fails on every insert.
 *
 * <p>{@code id} is null on creation; Spring Data JDBC populates it after insert.
 */
@Table("order_events")
public record OrderEventRecord(
    @Id Long id,
    @Column("symbol") String symbol,
    @Column("order_id") UUID orderId,
    @Column("counterparty_order_id") UUID counterpartyOrderId,
    @Column("sequence") Long sequence,
    @Column("engine_sequence") Long engineSequence,
    @Column("event_type") String eventType,
    @Column("payload") JsonPayload payload,
    @Column("occurred_at") Instant occurredAt) {

  /** Constructor used when creating new records (id is null — assigned by the database). */
  public OrderEventRecord(
      String symbol,
      UUID orderId,
      UUID counterpartyOrderId,
      Long sequence,
      Long engineSequence,
      String eventType,
      JsonPayload payload,
      Instant occurredAt) {
    this(
        null, symbol, orderId, counterpartyOrderId, sequence, engineSequence, eventType, payload,
        occurredAt);
  }
}
