package com.ygoleague.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Throwaway Postgres for repository tests, matching the docker-compose major version.
 * {@code @ServiceConnection} points the DataSource at it, so Flyway runs every migration and
 * Hibernate's {@code ddl-auto: validate} checks the entities against the real schema.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17");
    }
}
