package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TripMigrationIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final List<String> TRIP_TABLES =
      List.of("trip_plans", "trip_preferences", "trip_transport_modes", "trip_days");
  private static final List<String> AUTHENTICATED_OWNER_READ_TABLES =
      List.of("trip_preferences", "trip_transport_modes");

  @Autowired private JdbcTemplate jdbc;

  @Test
  void trip_timezone과_client_write_policy_제로가_catalog에_정확히_적용된다() {
    assertThat(
            jdbc.queryForMap(
                """
                select column_default, is_nullable
                from information_schema.columns
                where table_schema = 'public' and table_name = 'trip_plans'
                  and column_name = 'timezone'
                """))
        .containsEntry("column_default", "'Asia/Seoul'::text")
        .containsEntry("is_nullable", "NO");
    assertThat(
            jdbc.queryForObject(
                """
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conname = 'trip_plans_timezone_check'
                """,
                String.class))
        .contains("timezone = 'Asia/Seoul'::text");

    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from pg_policies
                where schemaname = 'public'
                  and tablename in ('trip_plans', 'trip_transport_modes', 'trip_days')
                  and cmd in ('INSERT', 'UPDATE', 'DELETE')
                """,
                Integer.class))
        .isZero();
  }

  @Test
  void trip_table의_anon_authenticated_service_role_ACL_matrix가_최소권한이다() {
    for (String table : TRIP_TABLES) {
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertPrivilege("anon", table, privilege, false);
        assertPrivilege(
            "authenticated",
            table,
            privilege,
            privilege.equals("SELECT") && AUTHENTICATED_OWNER_READ_TABLES.contains(table));
        assertPrivilege("service_role", table, privilege, true);
      }
      for (String role : List.of("anon", "authenticated", "service_role")) {
        assertPrivilege(role, table, "TRUNCATE", false);
      }
      for (String privilege : List.of("REFERENCES", "TRIGGER")) {
        assertPrivilege("service_role", table, privilege, false);
      }
    }
  }

  @Test
  void trip_revision과_날짜_guard가_actual_catalog에_적용된다() {
    assertThat(
            jdbc.queryForMap(
                """
                select column_default, is_nullable
                from information_schema.columns
                where table_schema = 'public' and table_name = 'trip_plans'
                  and column_name = 'revision'
                """))
        .containsEntry("column_default", "1")
        .containsEntry("is_nullable", "NO");
    assertThat(
            jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint where conname = 'trip_plans_revision_positive'",
                String.class))
        .contains("revision > 0");
    assertThat(
            jdbc.queryForObject(
                "select pg_get_functiondef('public.protect_trip_date_range()'::regprocedure::oid)",
                String.class))
        .contains("trip_schedule_versions")
        .contains("trip_transport_events")
        .contains("trip_accommodations");
  }

  private void assertPrivilege(String role, String table, String privilege, boolean expected) {
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege(?, 'public.' || ?, ?)",
                Boolean.class,
                role,
                table,
                privilege))
        .isEqualTo(expected);
  }
}
