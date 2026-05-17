package com.athena.adapter.persistence.repository;

import com.athena.adapter.persistence.entity.OrderEventRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

/** Spring Data JDBC repository for order events. All queries are explicit — no JPQL/HQL. */
public interface SpringDataOrderEventRepository
    extends CrudRepository<OrderEventRecord, Long> {

  List<OrderEventRecord> findByOrderIdOrderByIdAsc(String orderId);
}
