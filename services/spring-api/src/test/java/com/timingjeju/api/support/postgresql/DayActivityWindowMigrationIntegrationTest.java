package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class DayActivityWindowMigrationIntegrationTest {
  private static final String TARGET = "20260918000021_day_activity_window_pair.sql";

  @ParameterizedTest
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 기존_불완전시간을_변조없이_거부하고_정상값과_null쌍을_보존한다(String image) throws Exception {
    try (var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image)) {
      container.start();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      UUID owner = UUID.randomUUID();
      UUID trip = UUID.randomUUID();
      UUID day = UUID.randomUUID();
      jdbc.update("insert into auth.users (id,email) values (?, 'day239@example.test')", owner);
      jdbc.update(
          "insert into public.user_profiles (id,email) values (?, 'day239@example.test')", owner);
      jdbc.update(
          "insert into public.trip_plans (id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,source_mode,data_version) values (?,?,'day239-token','fixture','draft','2026-09-01','2026-09-01','Asia/Seoul','normal','fixture','239')",
          trip,
          owner);
      jdbc.update(
          "insert into public.trip_days (id,trip_plan_id,day_no,trip_date,start_time,end_time) values (?,?,1,'2026-09-01','09:00:30','18:00')",
          day,
          trip);
      Path migration = Path.of("../../supabase/migrations", TARGET);
      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, migration))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              jdbc.queryForObject(
                  "select start_time::text from public.trip_days where id=?", String.class, day))
          .isEqualTo("09:00:30");
      jdbc.update("update public.trip_days set start_time=null where id=?", day);
      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, migration))
          .isInstanceOf(IllegalStateException.class);
      jdbc.update("update public.trip_days set end_time=null where id=?", day);
      PostgreSqlTestContainerFactory.executeScript(container, migration);
      assertThat(
              jdbc.queryForObject(
                  "select convalidated from pg_constraint where conrelid='public.trip_days'::regclass and conname='trip_days_activity_window_pair_check'",
                  Boolean.class))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select start_time is null and end_time is null from public.trip_days where id=?",
                  Boolean.class,
                  day))
          .isTrue();
      for (String invalid :
          java.util.List.of(
              "start_time='09:00'",
              "start_time='09:00:30',end_time='18:00'",
              "start_time='09:00',end_time='24:00'",
              "start_time='18:00',end_time='18:00'")) {
        assertThatThrownBy(
                () -> jdbc.update("update public.trip_days set " + invalid + " where id=?", day))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
      }
      jdbc.update(
          "update public.trip_days set start_time='00:00',end_time='23:59' where id=?", day);
      assertThat(
              jdbc.queryForObject(
                  "select start_time::text||'/'||end_time::text from public.trip_days where id=?",
                  String.class,
                  day))
          .isEqualTo("00:00:00/23:59:00");
    }
  }
}
