package com.athena.adapter.kafka.config;

import com.athena.trading.avro.OrderCancelledEvent;
import com.athena.trading.avro.OrderPlacedEvent;
import com.athena.trading.avro.TradeExecutedEvent;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Configures a strongly-typed {@code KafkaTemplate<String, SpecificRecord>} for Avro publishing.
 * Uses Confluent's {@link KafkaAvroSerializer}; Schema Registry URL is read from
 * {@code spring.kafka.producer.properties.schema.registry.url} (set via env var or application.yml).
 *
 * <p>Overrides Spring Boot's default {@code KafkaTemplate<Object, Object>} auto-configuration via
 * explicit bean definition — Boot's {@code @ConditionalOnMissingBean(KafkaTemplate.class)} won't
 * create its default when we register ours.
 */
@Configuration
public class KafkaProducerConfiguration {

  @Bean
  public ProducerFactory<String, SpecificRecord> avroProducerFactory(
      KafkaProperties kafkaProperties) {

    Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));

    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());

    // Schema Registry URL must be set; defaults to localhost in application.yml
    props.putIfAbsent(
        KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081");

    // Enable idempotent producer: exactly-once delivery guarantee per partition
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, SpecificRecord> avroKafkaTemplate(
      ProducerFactory<String, SpecificRecord> avroProducerFactory) {
    return new KafkaTemplate<>(avroProducerFactory);
  }
}
