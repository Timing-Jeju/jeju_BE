package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PlannerConditionsMigrationIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private JdbcTemplate jdbc;

  @Test
  void 여행과_Day에_구조화_조건_칼럼이_존재한다() {
    assertThat(
            jdbc.queryForObject(
                """
        select count(*) from information_schema.columns where table_schema='public'
        and ((table_name='trip_plans' and column_name='planner_style_codes')
          or (table_name='trip_days' and column_name='lodging_place_id'))
        """,
                Integer.class))
        .isEqualTo(2);
  }
}
