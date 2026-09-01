package com.timingjeju.api.domain.accommodation.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationHttpResult;
import com.timingjeju.api.application.accommodation.AccommodationPatchValue;
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.accommodation.PatchAccommodationCommand;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcAccommodationConcurrencyIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("68100000-0000-0000-0000-000000000101");
  private static final UUID TRIP = UUID.fromString("68100000-0000-0000-0000-000000000102");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private AccommodationService service;

  @BeforeEach
  void setUp() {
    clean();
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
        ) values (?, ?, 'issue68-concurrent-token', '숙소 경합', 'draft',
          '2026-09-01', '2026-09-05', 'Asia/Seoul', 'normal', 'fixture', 'issue68-v1')
        """,
        TRIP,
        OWNER);
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.user_profiles where id = ?", OWNER);
    jdbc.update("delete from auth.users where id = ?", OWNER);
  }

  @Test
  void 같은_멱등키의_동시_POST는_한_row와_원응답_replay만_남긴다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> createAfter(start));
      var second = pool.submit(() -> createAfter(start));
      start.countDown();

      List<AccommodationHttpResult> results = List.of(first.get(), second.get());
      assertThat(results)
          .extracting(AccommodationHttpResult::replayed)
          .containsExactlyInAnyOrder(false, true);
      assertThat(results.get(0).snapshot().status()).isEqualTo(results.get(1).snapshot().status());
      assertThat(results.get(0).snapshot().contentType())
          .isEqualTo(results.get(1).snapshot().contentType());
      assertThat(results.get(0).snapshot().location())
          .isEqualTo(results.get(1).snapshot().location());
      assertThat(results.get(0).snapshot().etag()).isEqualTo(results.get(1).snapshot().etag());
      assertThat(results.get(0).snapshot().body())
          .containsExactly(results.get(1).snapshot().body());
      assertThat(count("trip_accommodations")).isOne();
      assertThat(count("accommodation_idempotency")).isOne();
      assertThat(revision()).isEqualTo(2L);
    }
  }

  @Test
  void 같은_revision의_동시_PATCH는_정확히_하나만_성공한다() throws Exception {
    service.create(OWNER, TRIP, "patch-source", expected(1), command());
    UUID accommodationId =
        jdbc.queryForObject(
            "select id from public.trip_accommodations where trip_plan_id = ?", UUID.class, TRIP);
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> patchAfter(start, accommodationId, "16:00"));
      var second = pool.submit(() -> patchAfter(start, accommodationId, "17:00"));
      start.countDown();

      assertThat(List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder("success", "TRIP_VERSION_CONFLICT");
      assertThat(revision()).isEqualTo(3L);
      assertThat(
              jdbc.queryForObject(
                  "select check_in_time::text from public.trip_accommodations where id = ?",
                  String.class,
                  accommodationId))
          .isIn("16:00:00", "17:00:00");
    }
  }

  private AccommodationHttpResult createAfter(CountDownLatch start) throws InterruptedException {
    start.await();
    return service.create(OWNER, TRIP, "concurrent-replay", expected(1), command());
  }

  private String patchAfter(CountDownLatch start, UUID accommodationId, String time)
      throws InterruptedException {
    start.await();
    try {
      service.patch(
          OWNER,
          TRIP,
          accommodationId,
          expected(2),
          PatchAccommodationCommand.withCheckInTime(
              AccommodationPatchValue.present(LocalTime.parse(time))));
      return "success";
    } catch (AccommodationException failure) {
      return failure.code();
    }
  }

  private CreateAccommodationCommand command() {
    return new CreateAccommodationCommand(
        null,
        "동시 숙소",
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-03"),
        LocalTime.parse("15:00"),
        LocalTime.parse("11:00"));
  }

  private TripExpectedRevision expected(long revision) {
    return new TripExpectedRevision(TRIP, revision);
  }

  private int count(String table) {
    return jdbc.queryForObject(
        "select count(*) from public." + table + " where trip_plan_id = ?", Integer.class, TRIP);
  }

  private long revision() {
    return jdbc.queryForObject(
        "select revision from public.trip_plans where id = ?", Long.class, TRIP);
  }
}
