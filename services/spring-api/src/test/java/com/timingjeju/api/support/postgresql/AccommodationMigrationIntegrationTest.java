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
class AccommodationMigrationIntegrationTest {
  private static final String TARGET = "20260907000002_trip_accommodation_contract.sql";
  private static final UUID OWNER = UUID.fromString("68200000-0000-0000-0000-000000000101");
  private static final UUID TRIP = UUID.fromString("68200000-0000-0000-0000-000000000102");
  private static final UUID ACCOMMODATION = UUID.fromString("68200000-0000-0000-0000-000000000103");
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
    jdbc.update("insert into auth.users (id, email) values (?, ?)", OWNER, OWNER + "@issue68.test");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        OWNER,
        OWNER + "@issue68.test");
    jdbc.update(
        """
        insert into public.trip_plans (
          id, user_id, public_token, title, status, start_date, end_date,
          timezone, user_pace, source_mode, data_version
        ) values (?, ?, 'issue68-legacy-token', '레거시 숙소', 'draft',
          '2026-09-01', '2026-09-05', 'Asia/Seoul', 'normal', 'fixture', 'issue68-v1')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        """
        insert into public.trip_accommodations (
          id, trip_plan_id, custom_name, check_in_date, check_out_date,
          check_in_time, check_out_time, sequence_no
        ) values (?, ?, '시간 미확정 숙소', '2026-09-01', '2026-09-03', null, null, 1)
        """,
        ACCOMMODATION,
        TRIP);
  }

  @AfterAll
  static void stop() {
    if (container != null) container.stop();
  }

  @Test
  void invalid_legacy_row가_있으면_migration은_원본을_보존한채_fail_closed한다() {
    Path target =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("supabase/migrations")
            .resolve(TARGET);

    assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, target))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForMap(
                "select custom_name,check_in_time,check_out_time from public.trip_accommodations where id = ?",
                ACCOMMODATION))
        .containsEntry("custom_name", "시간 미확정 숙소")
        .containsEntry("check_in_time", null)
        .containsEntry("check_out_time", null);
    assertThat(
            jdbc.queryForObject(
                "select to_regclass('public.accommodation_idempotency') is null", Boolean.class))
        .isTrue();
  }
}
