package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class TripCalendarInvariantCorrectionMigrationIntegrationTest {
  private static final String TARGET =
      "20260909000000_trip_calendar_child_invariant_correction.sql";
  private static final UUID OWNER = UUID.fromString("48900000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("48900000-0000-0000-0000-000000000002");
  private static final UUID PLACE = UUID.fromString("48900000-0000-0000-0000-000000000003");
  private static PostgreSQLContainer container;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateFromIssue48Schema() throws Exception {
    container = PostgreSqlTestContainerFactory.createBefore(TARGET);
    container.start();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    jdbc.update("insert into auth.users (id,email) values (?,?)", OWNER, OWNER + "@issue48.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", OWNER, OWNER + "@issue48.test");
    jdbc.update(
        """
        insert into public.tour_places
          (id,content_id,name,normalized_name,category,location,source_provider)
        values (?, 'issue48-correction-place', '교정 장소', '교정 장소', 'VE',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography, 'fixture')
        """,
        PLACE);
    jdbc.update(
        """
        insert into public.trip_plans
          (id,user_id,public_token,status,start_date,end_date,timezone,user_pace,source_mode,data_version)
        values (?,?,'issue48-correction-trip','draft','2026-09-01','2026-09-03',
          'Asia/Seoul','normal','fixture','issue48-correction')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        "insert into public.trip_transport_events (trip_plan_id,event_type,transport_type,terminal_name,scheduled_at) values (?,'departure','flight','제주공항','2026-09-03T00:00:00Z')",
        TRIP);
    jdbc.update(
        "insert into public.trip_place_preferences (trip_plan_id,place_id,preference_type,target_day_no,priority) values (?,?,'must_visit',3,90)",
        TRIP,
        PLACE);

    Path migration =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("supabase/migrations")
            .resolve(TARGET);
    PostgreSqlTestContainerFactory.executeScript(container, migration);
  }

  @AfterAll
  static void stop() {
    if (container != null) container.stop();
  }

  @Test
  void 기존_valid_child를_보존하고_root확장과축소를_named23514로_거부한다() {
    assertNamedConstraint(
        () -> jdbc.update("update public.trip_plans set end_date='2026-09-04' where id=?", TRIP));
    assertNamedConstraint(
        () -> jdbc.update("update public.trip_plans set end_date='2026-09-02' where id=?", TRIP));

    assertThat(
            jdbc.queryForObject(
                "select concat(revision,':',start_date,':',end_date) from public.trip_plans where id=?",
                String.class,
                TRIP))
        .isEqualTo("1:2026-09-01:2026-09-03");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_place_preferences where trip_plan_id=? and target_day_no=3",
                Integer.class,
                TRIP))
        .isOne();
  }

  @Test
  void corrective_guard는_data_api_roles에_execute를_노출하지_않는다() {
    for (String role : new String[] {"anon", "authenticated", "service_role"}) {
      assertThat(
              jdbc.queryForObject(
                  "select has_function_privilege(?, 'public.protect_trip_date_range()', 'EXECUTE')",
                  Boolean.class,
                  role))
          .as(role)
          .isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                """
                select not exists (
                  select 1 from pg_proc p
                  cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                  where p.oid='public.protect_trip_date_range()'::regprocedure
                    and acl.grantee=0 and acl.privilege_type='EXECUTE'
                )
                """,
                Boolean.class))
        .isTrue();
  }

  private static void assertNamedConstraint(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            failure -> {
              Throwable current = failure;
              while (current != null && !(current instanceof SQLException)) {
                current = current.getCause();
              }
              assertThat(current).isInstanceOf(PSQLException.class);
              PSQLException postgres = (PSQLException) current;
              assertThat(postgres.getSQLState()).isEqualTo("23514");
              assertThat(postgres.getServerErrorMessage().getConstraint())
                  .isEqualTo("ck_trip_calendar_children_match_root");
            });
  }
}
