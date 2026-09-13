package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class GenerationLifecycleMigrationIntegrationTest {
  @Test
  void 생성_run과_후보에_복구와_만료용_필드가_존재한다() {
    try (var container = PostgreSqlTestContainerFactory.create()) {
      container.start();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      assertThat(
              jdbc.queryForList(
                  "select column_name from information_schema.columns where table_schema='public' and table_name='itinerary_generation_runs'",
                  String.class))
          .contains(
              "attempt_count",
              "fencing_token",
              "lease_owner",
              "lease_expires_at",
              "heartbeat_at",
              "next_attempt_at",
              "outcome",
              "retained_until");
      assertThat(
              jdbc.queryForList(
                  "select column_name from information_schema.columns where table_schema='public' and table_name='itinerary_generation_candidates'",
                  String.class))
          .contains("strategy", "expires_at", "feasibility");
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('authenticated', 'public.itinerary_generation_runs', 'select')",
                  Boolean.class))
          .isFalse();
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('anon', 'public.itinerary_generation_candidates', 'select')",
                  Boolean.class))
          .isFalse();
    }
  }
}
