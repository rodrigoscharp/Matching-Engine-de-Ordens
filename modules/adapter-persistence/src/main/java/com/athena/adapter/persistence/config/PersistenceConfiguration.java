package com.athena.adapter.persistence.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Activates Spring Data JDBC repositories and transaction management for the persistence adapter.
 *
 * <p>Extends {@link AbstractJdbcConfiguration} so {@link #userConverters()} is honoured. A
 * free-standing {@code JdbcCustomConversions} bean is not enough: the mapping context is built
 * from this class, and without the converters registered here it classifies {@code JsonPayload} as
 * a nested entity and silently omits the column from every INSERT.
 *
 * <p>Boot's own JDBC auto-configuration backs off in the presence of this bean
 * ({@code @ConditionalOnMissingBean(AbstractJdbcConfiguration.class)}).
 */
@Configuration
@EnableJdbcRepositories(basePackages = "com.athena.adapter.persistence.repository")
@EnableTransactionManagement
public class PersistenceConfiguration extends AbstractJdbcConfiguration {

  @Override
  protected List<?> userConverters() {
    return List.of(
        JsonPayloadConverters.ToPGobject.INSTANCE, JsonPayloadConverters.FromPGobject.INSTANCE);
  }
}
