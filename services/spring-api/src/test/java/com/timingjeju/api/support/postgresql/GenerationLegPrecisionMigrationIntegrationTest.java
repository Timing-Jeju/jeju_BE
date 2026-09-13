package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class GenerationLegPrecisionMigrationIntegrationTest {
  @Test
  void 초단위_시간_분해와_null_분을_함께_검증한다() {
    try (var container = PostgreSqlTestContainerFactory.create()) {
      container.start();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      String facts =
          """
          {"generation":{"schemaVersion":1,"precision":{"walkNanos":0,
          "rideNanos":630000000000,"transferNanos":0,"waitNanos":300000000000,
          "roundingNanos":30000000000}}}
          """;
      String sql =
          "select timing_jeju_planner_private.valid_leg_precision(?::jsonb, 'public_transit', 0, 5, ?::integer, 0, 16)";
      assertThat(jdbc.queryForObject(sql, Boolean.class, facts, null)).isTrue();
      assertThat(jdbc.queryForObject(sql, Boolean.class, facts, 10)).isFalse();
      assertThat(jdbc.queryForObject(sql, Boolean.class, "{}", null)).isFalse();
      assertThat(
              jdbc.queryForObject(
                  sql, Boolean.class, facts.replace("30000000000}", "60000000000}"), null))
          .isFalse();
    }
  }
}
