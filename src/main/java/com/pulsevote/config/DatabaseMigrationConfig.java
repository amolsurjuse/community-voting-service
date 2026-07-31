package com.pulsevote.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class DatabaseMigrationConfig {
    @Bean
    Flyway flyway(DataSource dataSource,
                  @Value("${spring.flyway.locations:classpath:db/migration}") String locations) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.split(","))
                .load();
        flyway.migrate();
        return flyway;
    }
}
