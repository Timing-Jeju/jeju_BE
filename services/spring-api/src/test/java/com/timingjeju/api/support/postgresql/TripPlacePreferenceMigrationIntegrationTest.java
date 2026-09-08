package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class TripPlacePreferenceMigrationIntegrationTest {
  private static final String TARGET = "20260908000000_trip_place_preference_contract.sql";
  private static final UUID OWNER = UUID.fromString("48300000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("48300000-0000-0000-0000-000000000002");
  private static final UUID PLACE = UUID.fromString("48300000-0000-0000-0000-000000000003");
  private static PostgreSQLContainer container;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void startAtPreviousSchema() {
    container = PostgreSqlTestContainerFactory.createBefore(TARGET);
    container.start();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("insert into auth.users (id,email) values (?,?)", OWNER, OWNER + "@issue48.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", OWNER, OWNER + "@issue48.test");
    jdbc.update(
        """
        insert into public.tour_places (id,content_id,name,normalized_name,category,location,source_provider)
        values (?, 'issue48-legacy-place', '중복 장소', '중복 장소', 'VE',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography, 'fixture')
        """,
        PLACE);
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,status,start_date,end_date,timezone,user_pace,source_mode,data_version
        ) values (?,?,'issue48-legacy-trip','draft','2026-09-01','2026-09-03',
          'Asia/Seoul','normal','fixture','issue48')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        """
        insert into public.trip_place_preferences (
          trip_plan_id,place_id,preference_type,target_day_no,priority
        ) values
          (?,?,'must_visit',1,90),
          (?,?,'avoid',null,10)
        """,
        TRIP,
        PLACE,
        TRIP,
        PLACE);
  }

  @AfterAll
  static void stop() {
    if (container != null) container.stop();
  }

  @Test
  void 상충_role_legacy_row가_있으면_migration은_원본을_보존한채_fail_closed한다() {
    Path target =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("supabase/migrations")
            .resolve(TARGET);

    assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, target))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_place_preferences where trip_plan_id=? and place_id=?",
                Integer.class,
                TRIP,
                PLACE))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from pg_constraint
                where conrelid='public.trip_place_preferences'::regclass
                  and conname='trip_place_preferences_pkey'
                  and pg_get_constraintdef(oid)='PRIMARY KEY (trip_plan_id, place_id, preference_type)'
                """,
                Integer.class))
        .isEqualTo(1);
  }
}
