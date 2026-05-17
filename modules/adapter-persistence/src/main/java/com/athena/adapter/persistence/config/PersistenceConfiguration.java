package com.athena.adapter.persistence.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Activates Spring Data JDBC repositories and transaction management for the persistence adapter. */
@Configuration
@EnableJdbcRepositories(
    basePackages = "com.athena.adapter.persistence.repository")
@EnableTransactionManagement
public class PersistenceConfiguration {}
