package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlacePreference;
import com.timingjeju.api.application.trip.UpdateTripPlacePreferencesCommand;
import com.timingjeju.api.application.trip.service.TripPlacePreferencesService;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
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
class JdbcTripPlacePreferencesConcurrencyIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("48400000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("48400000-0000-0000-0000-000000000002");
  private static final UUID PLACE_A = UUID.fromString("48400000-0000-0000-0000-000000000003");
  private static final UUID PLACE_B = UUID.fromString("48400000-0000-0000-0000-000000000004");
  private static final Instant ORIGINAL_AT = Instant.parse("2026-09-01T00:00:00Z");
  private static final CurrentUser USER =
      new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TripPlacePreferencesService service;

  @BeforeEach
  void setUp() {
    clean();
    jdbc.update("insert into auth.users (id,email) values (?,?)", OWNER, OWNER + "@issue48.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", OWNER, OWNER + "@issue48.test");
    insertPlace(PLACE_A, "a");
    insertPlace(PLACE_B, "b");
    jdbc.update(
        "insert into public.saved_places (user_id,place_id) values (?,?),(?,?)",
        OWNER,
        PLACE_A,
        OWNER,
        PLACE_B);
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,status,start_date,end_date,timezone,user_pace,
          source_mode,data_version,created_at,updated_at
        ) values (?,?,'issue48-concurrent-trip','draft','2026-09-01','2026-09-03',
          'Asia/Seoul','normal','fixture','issue48',?,?)
        """,
        TRIP,
        OWNER,
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from public.trip_plans where id=?", TRIP);
    jdbc.update("delete from public.saved_places where user_id=?", OWNER);
    jdbc.update("delete from public.tour_places where id in (?,?)", PLACE_A, PLACE_B);
    jdbc.update("delete from public.user_profiles where id=?", OWNER);
    jdbc.update("delete from auth.users where id=?", OWNER);
  }

  @Test
  void 같은_ETag의_동시_replace는_한_writer만_성공하고_lost_update가_없다() throws Exception {
    String etag = TripEntityTag.strong(TRIP, 1);
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> replaceAfter(start, etag, PLACE_A, "must_visit"));
      var second = pool.submit(() -> replaceAfter(start, etag, PLACE_B, "avoid"));
      start.countDown();

      assertThat(List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder("success", "TRIP_VERSION_CONFLICT");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_place_preferences where trip_plan_id=?",
                  Integer.class,
                  TRIP))
          .isOne();
      assertThat(
              jdbc.queryForObject(
                  "select place_id from public.trip_place_preferences where trip_plan_id=?",
                  UUID.class,
                  TRIP))
          .isIn(PLACE_A, PLACE_B);
    }
  }

  private String replaceAfter(CountDownLatch start, String etag, UUID placeId, String type)
      throws InterruptedException {
    start.await();
    try {
      service.replace(
          USER,
          TRIP,
          etag,
          new UpdateTripPlacePreferencesCommand(
              List.of(new TripPlacePreference(placeId, type, null, 50))));
      return "success";
    } catch (TripException failure) {
      return failure.code();
    }
  }

  private void insertPlace(UUID id, String suffix) {
    jdbc.update(
        """
        insert into public.tour_places (
          id,content_id,name,normalized_name,category,location,source_provider
        ) values (?,?,?,?,'VE',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture')
        """,
        id,
        "issue48-concurrent-" + suffix,
        suffix,
        suffix);
  }
}
