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
  private static final String PG16 = "postgis/postgis:16-3.4";
  private static final String PG17 = "postgis/postgis:17-3.5";
  private static final String FIRST_PREFIX = "20260730000000_database_integrity_hardening.sql";
  private static final String SECOND_PREFIX = "20260730010000_external_ingestion_foundation.sql";

  @Test
  void PG17과_다른_prefix가_먼저_실행되어도_재사용과_case격리가_순서에_의존하지_않는다() throws Exception {
    int baselineImages = PostgreSqlLauncherSessionPool.startedImageCount();
    PostgreSQLContainer precedingPg17 =
        PostgreSqlTestContainerFactory.createBefore(SECOND_PREFIX, PG17);
    PostgreSQLContainer precedingPg16 =
        PostgreSqlTestContainerFactory.createBefore(SECOND_PREFIX, PG16);
    PostgreSQLContainer first = PostgreSqlTestContainerFactory.createBefore(FIRST_PREFIX, PG16);
    PostgreSQLContainer second = PostgreSqlTestContainerFactory.createBefore(FIRST_PREFIX, PG16);
    PostgreSQLContainer otherPrefix =
        PostgreSqlTestContainerFactory.createBefore(SECOND_PREFIX, PG16);
    try {
      precedingPg17.start();
      precedingPg16.start();
      String precedingTemplate = PostgreSqlLauncherSessionPool.templateIdentity(precedingPg16);
      int baselineTemplates = PostgreSqlLauncherSessionPool.cachedTemplateCount(PG16);

      assertThat(PostgreSqlLauncherSessionPool.startedImageCount())
          .isBetween(baselineImages, baselineImages + 2);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var firstStart = executor.submit(first::start);
        var secondStart = executor.submit(second::start);
        firstStart.get();
        secondStart.get();
      }
      otherPrefix.start();

      assertThat(PostgreSqlLauncherSessionPool.startedImageCount())
          .isBetween(baselineImages, baselineImages + 2);
      assertThat(PostgreSqlLauncherSessionPool.cachedTemplateCount(PG16))
          .isBetween(baselineTemplates, baselineTemplates + 1);
      assertThat(PostgreSqlLauncherSessionPool.templateIdentity(second))
          .isEqualTo(PostgreSqlLauncherSessionPool.templateIdentity(first));
      assertThat(PostgreSqlLauncherSessionPool.templateIdentity(otherPrefix))
          .isEqualTo(precedingTemplate)
          .isNotEqualTo(PostgreSqlLauncherSessionPool.templateIdentity(first));
      assertThat(second.getContainerId()).isEqualTo(first.getContainerId());
      assertThat(precedingPg17.getContainerId()).isNotEqualTo(first.getContainerId());
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
      precedingPg16.stop();
      precedingPg17.stop();
    }
  }

  @Test
  void launcher_session의_image별_실제_물리_start_상한은_1이다() {
    PostgreSQLContainer pg16 = PostgreSqlTestContainerFactory.createBefore(SECOND_PREFIX, PG16);
    PostgreSQLContainer pg17 = PostgreSqlTestContainerFactory.createBefore(SECOND_PREFIX, PG17);
    try {
      pg16.start();
      pg17.start();
      assertThat(PostgreSqlLauncherSessionPool.physicalStartCount(PG16)).isOne();
      assertThat(PostgreSqlLauncherSessionPool.physicalStartCount(PG17)).isOne();
    } finally {
      pg17.stop();
      pg16.stop();
    }
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer container) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }
}
