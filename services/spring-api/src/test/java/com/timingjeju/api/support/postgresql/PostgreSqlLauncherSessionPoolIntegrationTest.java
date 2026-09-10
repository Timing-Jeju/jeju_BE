package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PostgreSqlLauncherSessionPoolIntegrationTest {
  private static final String IMAGE = "postgis/postgis:16-3.4";
  private static final String FIRST_PREFIX = "20260730000000_database_integrity_hardening.sql";
  private static final String SECOND_PREFIX = "20260730010000_external_ingestion_foundation.sql";

  @Test
  void 같은_image는_물리_container를_공유하고_prefix_template과_case_database를_격리한다() throws Exception {
    PostgreSQLContainer first = PostgreSqlTestContainerFactory.createBefore(FIRST_PREFIX, IMAGE);
    PostgreSQLContainer second = PostgreSqlTestContainerFactory.createBefore(FIRST_PREFIX, IMAGE);
    PostgreSQLContainer otherPrefix =
        PostgreSqlTestContainerFactory.createBefore(SECOND_PREFIX, IMAGE);
    try {
      try (var executor = Executors.newFixedThreadPool(2)) {
        var firstStart = executor.submit(first::start);
        var secondStart = executor.submit(second::start);
        firstStart.get();
        secondStart.get();
      }
      otherPrefix.start();

      assertThat(PostgreSqlLauncherSessionPool.startedImageCount()).isOne();
      assertThat(PostgreSqlLauncherSessionPool.cachedTemplateCount(IMAGE)).isEqualTo(2);
      assertThat(second.getContainerId()).isEqualTo(first.getContainerId());
      assertThat(second.getDatabaseName()).isNotEqualTo(first.getDatabaseName());
      assertThat(otherPrefix.getDatabaseName()).isNotEqualTo(first.getDatabaseName());

      JdbcTemplate firstJdbc = jdbc(first);
      JdbcTemplate secondJdbc = jdbc(second);
      firstJdbc.execute("create table public.issue_247_pool_isolation(id integer primary key)");
      firstJdbc.update("insert into public.issue_247_pool_isolation values (247)");

      assertThat(
              secondJdbc.queryForObject(
                  "select to_regclass('public.issue_247_pool_isolation') is null", Boolean.class))
          .isTrue();
    } finally {
      otherPrefix.stop();
      second.stop();
      first.stop();
    }
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer container) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }
}
