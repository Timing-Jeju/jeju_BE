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
class TransportEventMigrationIntegrationTest {
  private static final String TARGET = "20260907000000_trip_transport_event_contract.sql";
  private static final UUID OWNER = UUID.fromString("47300000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("47300000-0000-0000-0000-000000000002");
  private static final UUID PLACE = UUID.fromString("47300000-0000-0000-0000-000000000003");
  private static final UUID EVENT = UUID.fromString("47300000-0000-0000-0000-000000000004");
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
    jdbc.update("insert into auth.users (id,email) values (?,?)", OWNER, OWNER + "@issue47.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", OWNER, OWNER + "@issue47.test");
    jdbc.update(
        """
        insert into public.tour_places (id,content_id,name,normalized_name,category,location,source_provider)
        values (?, 'issue47-legacy-place', '제주항', '제주항', 'PC', ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography, 'fixture')
        """,
        PLACE);
    jdbc.update(
        """
        insert into public.trip_plans (id,user_id,public_token,status,start_date,end_date,timezone,user_pace,source_mode,data_version)
        values (?, ?, 'issue47-legacy-trip', 'draft', '2026-09-01', '2026-09-05', 'Asia/Seoul', 'normal', 'fixture', 'issue47')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        """
        insert into public.trip_transport_events (
          id,trip_plan_id,event_type,transport_type,terminal_place_id,terminal_name,scheduled_at
        ) values (?, ?, 'arrival', 'ferry', ?, '양쪽 터미널', '2026-09-01T09:00:00+09:00')
        """,
        EVENT,
        TRIP,
        PLACE);
  }

  @AfterAll
  static void stop() {
    if (container != null) container.stop();
  }

  @Test
  void invalid_legacy_XOR_row가_있으면_migration은_원본을_보존한채_fail_closed한다() {
    Path target =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("supabase/migrations")
            .resolve(TARGET);

    assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, target))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForMap(
                "select terminal_place_id,terminal_name from public.trip_transport_events where id = ?",
                EVENT))
        .containsEntry("terminal_place_id", PLACE)
        .containsEntry("terminal_name", "양쪽 터미널");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_constraint where conrelid='public.trip_transport_events'::regclass and conname='ck_trip_transport_events_exactly_one_terminal'",
                Integer.class))
        .isZero();
  }
}
