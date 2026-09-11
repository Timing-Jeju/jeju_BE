package com.timingjeju.api.domain.transportevent.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.application.transportevent.service.TransportEventService;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.time.OffsetDateTime;
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
class JdbcTransportEventConcurrencyIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("47400000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("47400000-0000-0000-0000-000000000002");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransportEventService service;

  @BeforeEach
  void setUp() {
    clean();
    jdbc.update("insert into auth.users (id,email) values (?,?)", OWNER, OWNER + "@issue47.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", OWNER, OWNER + "@issue47.test");
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,status,start_date,end_date,timezone,user_pace,source_mode,data_version
        ) values (?, ?, 'issue47-concurrent-trip', 'draft', '2026-09-01', '2026-09-05',
          'Asia/Seoul', 'normal', 'fixture', 'issue47')
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
  void 같은_revision의_동시_PUT은_한_writer만_성공하고_lost_update가_없다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> putAfter(start, "KE1001"));
      var second = pool.submit(() -> putAfter(start, "OZ8901"));
      start.countDown();

      assertThat(List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder("success", "TRIP_VERSION_CONFLICT");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_transport_events where trip_plan_id = ?",
                  Integer.class,
                  TRIP))
          .isOne();
      assertThat(
              jdbc.queryForObject(
                  "select transport_number from public.trip_transport_events where trip_plan_id = ?",
                  String.class,
                  TRIP))
          .isIn("KE1001", "OZ8901");
      assertThat(
              jdbc.queryForObject(
                  "select revision from public.trip_plans where id = ?", Long.class, TRIP))
          .isEqualTo(2L);
    }
  }

  @Test
  void 같은_revision의_PUT_DELETE경합도_한_writer만_성공한다() throws Exception {
    service.put(OWNER, TRIP, new TripExpectedRevision(TRIP, 1), command("KE1001"));
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var put = pool.submit(() -> putAfter(start, "OZ8901", 2));
      var delete = pool.submit(() -> deleteAfter(start, 2));
      start.countDown();

      assertThat(List.of(put.get(), delete.get()))
          .containsExactlyInAnyOrder("success", "TRIP_VERSION_CONFLICT");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_transport_events where trip_plan_id = ?",
                  Integer.class,
                  TRIP))
          .isIn(0, 1);
      assertThat(
              jdbc.queryForObject(
                  "select revision from public.trip_plans where id = ?", Long.class, TRIP))
          .isEqualTo(3L);
    }
  }

  private String putAfter(CountDownLatch start, String number) throws InterruptedException {
    return putAfter(start, number, 1);
  }

  private String putAfter(CountDownLatch start, String number, long revision)
      throws InterruptedException {
    start.await();
    try {
      service.put(OWNER, TRIP, new TripExpectedRevision(TRIP, revision), command(number));
      return "success";
    } catch (TransportEventException failure) {
      return failure.code();
    }
  }

  private String deleteAfter(CountDownLatch start, long revision) throws InterruptedException {
    start.await();
    try {
      service.delete(OWNER, TRIP, "arrival", new TripExpectedRevision(TRIP, revision));
      return "success";
    } catch (TransportEventException failure) {
      return failure.code();
    }
  }

  private PutTransportEventCommand command(String number) {
    return new PutTransportEventCommand(
        "arrival",
        "flight",
        null,
        "제주국제공항",
        OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
        number,
        null);
  }
}
