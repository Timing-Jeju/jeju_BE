package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.trip.CreateTripCommand;
import com.timingjeju.api.application.trip.CreateTripRecord;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcTripCreateRollbackIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("44000000-0000-0000-0000-000000000901");
  private static final UUID TRIP = UUID.fromString("44000000-0000-0000-0000-000000000902");
  private static final UUID DAY = UUID.fromString("44000000-0000-0000-0000-000000000903");
  private static final String KEY = "44000000-0000-0000-0000-000000000904";

  @Autowired private JdbcTripStore store;
  @Autowired private IdempotencyUseCase idempotency;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUpOwner() {
    cleanFixtures();
    jdbc.update(
        "insert into auth.users (id, email, raw_user_meta_data) values (?, ?, '{}'::jsonb)",
        OWNER,
        "trip-rollback@issue44.test");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        OWNER,
        "trip-rollback@issue44.test");
  }

  @AfterEach
  void cleanFixtures() {
    jdbc.update("delete from public.api_idempotency_records where owner_sub = ?", OWNER);
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.user_profiles where id = ?", OWNER);
    jdbc.update("delete from auth.users where id = ?", OWNER);
  }

  @Test
  void day_insert_실패시_root_transport_days와_idempotency가_모두_rollback된다() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    IdempotencyRequest request =
        IdempotencyRequest.create(OWNER, "POST", "/api/v1/trips", KEY, body);

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    request,
                    () -> {
                      store.create(recordWithDuplicateDayId());
                      throw new AssertionError("day constraint가 실패해야 합니다.");
                    }))
        .isInstanceOf(TripException.class);

    assertThat(count("trip_plans", "id", TRIP)).isZero();
    assertThat(count("trip_transport_modes", "trip_plan_id", TRIP)).isZero();
    assertThat(count("trip_days", "trip_plan_id", TRIP)).isZero();
    assertThat(count("api_idempotency_records", "owner_sub", OWNER)).isZero();
  }

  private CreateTripRecord recordWithDuplicateDayId() {
    return new CreateTripRecord(
        OWNER,
        TRIP,
        "44000000-0000-0000-0000-000000000905",
        new CreateTripCommand(
            "제주 여행",
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-09-02"),
            "Asia/Seoul",
            "normal",
            List.of(new TripTransportMode("public_transit", 1, true))),
        List.of(DAY, DAY),
        Instant.parse("2026-08-25T00:00:00Z"));
  }

  private int count(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "select count(*) from public." + table + " where " + column + " = ?", Integer.class, id);
  }
}
