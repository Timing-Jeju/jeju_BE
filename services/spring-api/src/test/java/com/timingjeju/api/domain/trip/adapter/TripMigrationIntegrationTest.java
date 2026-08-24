package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TripMigrationIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private JdbcTemplate jdbc;

  @Test
  void trip_timezone과_owner_write_RLS가_catalog에_정확히_적용된다() {
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

    List<Map<String, Object>> policies =
        jdbc.queryForList(
            """
            select policyname, tablename, cmd, roles::text as roles,
                   replace(regexp_replace(coalesce(qual, ''), '\\s+', '', 'g'), 'public.', '') as qual,
                   replace(regexp_replace(coalesce(with_check, ''), '\\s+', '', 'g'), 'public.', '') as with_check
            from pg_policies
            where schemaname = 'public'
              and policyname in (
                'trip_plans_owner_insert', 'trip_plans_owner_update', 'trip_plans_owner_delete',
                'trip_transport_modes_owner_insert', 'trip_transport_modes_owner_update',
                'trip_transport_modes_owner_delete', 'trip_days_owner_insert',
                'trip_days_owner_update', 'trip_days_owner_delete'
              )
            order by policyname
            """);
    assertThat(policies).hasSize(9);
    assertPolicy(
        policies,
        "trip_plans_owner_insert",
        "trip_plans",
        "INSERT",
        "",
        "((user_id=(SELECTauth.uid()ASuid))AND(session_idISNULL))");
    assertPolicy(
        policies,
        "trip_plans_owner_update",
        "trip_plans",
        "UPDATE",
        "(user_id=(SELECTauth.uid()ASuid))",
        "((user_id=(SELECTauth.uid()ASuid))AND(session_idISNULL))");
    assertPolicy(
        policies,
        "trip_plans_owner_delete",
        "trip_plans",
        "DELETE",
        "(user_id=(SELECTauth.uid()ASuid))",
        "");
    assertChildPolicies(policies, "trip_transport_modes");
    assertChildPolicies(policies, "trip_days");
  }

  @Test
  void trip_table의_anon_authenticated_service_role_ACL_matrix가_최소권한이다() {
    for (String table : List.of("trip_plans", "trip_transport_modes", "trip_days")) {
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertPrivilege("anon", table, privilege, false);
        assertPrivilege("authenticated", table, privilege, false);
        assertPrivilege("service_role", table, privilege, true);
      }
      for (String role : List.of("anon", "authenticated", "service_role")) {
        assertPrivilege(role, table, "TRUNCATE", false);
      }
    }
  }

  private static void assertChildPolicies(List<Map<String, Object>> policies, String table) {
    String predicate = "(SELECTowns_trip_plan(" + table + ".trip_plan_id)ASowns_trip_plan)";
    assertPolicy(policies, table + "_owner_insert", table, "INSERT", "", predicate);
    assertPolicy(policies, table + "_owner_update", table, "UPDATE", predicate, predicate);
    assertPolicy(policies, table + "_owner_delete", table, "DELETE", predicate, "");
  }

  private static void assertPolicy(
      List<Map<String, Object>> policies,
      String name,
      String table,
      String command,
      String qual,
      String withCheck) {
    assertThat(policies)
        .anySatisfy(
            policy ->
                assertThat(policy)
                    .containsEntry("policyname", name)
                    .containsEntry("tablename", table)
                    .containsEntry("cmd", command)
                    .containsEntry("roles", "{authenticated}")
                    .containsEntry("qual", qual)
                    .containsEntry("with_check", withCheck));
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
