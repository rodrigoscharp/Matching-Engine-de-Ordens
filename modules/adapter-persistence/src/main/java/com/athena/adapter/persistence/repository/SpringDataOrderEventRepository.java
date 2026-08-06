package com.athena.adapter.persistence.repository;

import com.athena.adapter.persistence.entity.OrderEventRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/** Spring Data JDBC repository for order events. All queries are explicit — no JPQL/HQL. */
public interface SpringDataOrderEventRepository
    extends CrudRepository<OrderEventRecord, Long> {

  /**
   * Every event touching this order, including trades where it was the counterparty. A trade names
   * two orders; matching only {@code order_id} hides half of every execution.
   */
  @Query(
      """
      SELECT * FROM order_events
      WHERE order_id = :orderId OR counterparty_order_id = :orderId
      ORDER BY id ASC
      """)
  List<OrderEventRecord> findByOrderIdOrCounterparty(@Param("orderId") UUID orderId);

  /**
   * The whole log in engine order, for rebuilding books at startup.
   *
   * <p>Ordered by {@code engine_sequence}, not {@code id}: rows are written from virtual threads
   * and can land out of order, so insertion order does not reproduce the decisions that built the
   * book. Rows predating that column sort first, by id — the best available guess for them.
   */
  @Query("SELECT * FROM order_events ORDER BY engine_sequence ASC NULLS FIRST, id ASC")
  List<OrderEventRecord> findAllInEngineOrder();

  /** High-water mark of the engine sequence, so a restart resumes counting instead of colliding. */
  @Query("SELECT COALESCE(MAX(engine_sequence), 0) FROM order_events")
  long findMaxEngineSequence();
}
