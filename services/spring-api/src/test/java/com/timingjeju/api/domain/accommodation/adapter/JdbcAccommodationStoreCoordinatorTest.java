package com.timingjeju.api.domain.accommodation.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.accommodation.AccommodationCreateRecord;
import com.timingjeju.api.application.accommodation.AccommodationDeleteRecord;
import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationHttpSnapshot;
import com.timingjeju.api.application.accommodation.AccommodationPatchRecord;
import com.timingjeju.api.application.accommodation.AccommodationPatchValue;
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.accommodation.PatchAccommodationCommand;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationOperation;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripAggregateMutationState;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripScheduleEffect;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
@SuppressWarnings("unchecked")
class JdbcAccommodationStoreCoordinatorTest {
  private static final UUID OWNER = UUID.fromString("68000000-0000-0000-0000-000000000911");
  private static final UUID TRIP = UUID.fromString("68000000-0000-0000-0000-000000000912");
  private static final UUID ACCOMMODATION = UUID.fromString("68000000-0000-0000-0000-000000000913");
  private static final UUID EXISTING = UUID.fromString("68000000-0000-0000-0000-000000000914");
  private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

  @Test
  void completed_snapshot_replay는_stale와_terminal_coordinator검사보다_먼저_반환한다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    ResultSet result = mock(ResultSet.class);
    when(result.getBytes("response_body"))
        .thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(result.getInt("response_status")).thenReturn(201);
    when(result.getString("response_content_type")).thenReturn("application/json");
    when(result.getString("response_location"))
        .thenReturn("/api/v1/trips/replay/accommodations/one");
    when(result.getString("response_etag")).thenReturn("\"trip-2\"");
    when(result.getString("request_hash")).thenReturn("a".repeat(64));
    when(jdbc.query(
            contains("from public.accommodation_idempotency"),
            any(RowMapper.class),
            any(Object[].class)))
        .thenAnswer(
            invocation -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(result, 0)));
    AtomicBoolean coordinatorCalled = new AtomicBoolean();
    TripAggregateMutationCoordinator coordinator =
        new TripAggregateMutationCoordinator() {
          @Override
          public <T> TripAggregateMutationCommit<T> execute(
              UUID ownerId,
              UUID tripId,
              long expectedRevision,
              Instant updatedAt,
              TripAggregateMutationOperation<T> operation) {
            coordinatorCalled.set(true);
            throw TripException.terminalStateConflict();
          }

          @Override
          public <T> TripAggregateMutationCommit<T> executeMonotonic(
              UUID ownerId,
              UUID tripId,
              long expectedRevision,
              Instant requestedAt,
              com.timingjeju.api.application.trip.TripAggregateTimestampedMutationOperation<T>
                  operation) {
            coordinatorCalled.set(true);
            throw TripException.terminalStateConflict();
          }
        };

    var replay = new JdbcAccommodationStore(jdbc, coordinator).create(createRecord());

    assertThat(replay.replaySnapshot().etag()).isEqualTo("\"trip-2\"");
    assertThat(coordinatorCalled).isFalse();
  }

  @Test
  void common_TripException은_accommodation_canonical_error로만_번역된다() {
    assertTranslated(TripException.notFound(), "TRIP_NOT_FOUND");
    assertTranslated(TripException.versionConflict(), "TRIP_VERSION_CONFLICT");
    assertTranslated(TripException.terminalStateConflict(), "TRIP_VERSION_CONFLICT");
    assertTranslated(TripException.constraintViolation(), "ACCOMMODATION_CONCURRENT_CONFLICT");
    assertTranslated(TripException.dataUnavailable(), "ACCOMMODATION_DATA_UNAVAILABLE");
  }

  @Test
  void 두번째_숙소_append는_DB_compaction과_같은_sequence2를_response와_replay에_남긴다() throws Exception {
    JdbcAccommodationStore store =
        new JdbcAccommodationStore(
            jdbcWithRows(
                List.of(
                    new AccommodationRow(
                        EXISTING,
                        "첫 숙소",
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-02"),
                        1))),
            applyingCoordinator(state(null)));
    AccommodationService service =
        new AccommodationService(
            store, () -> ACCOMMODATION, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());

    var created =
        service.create(
            OWNER,
            TRIP,
            "append-second",
            1,
            create("둘째 숙소", LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-03")));

    assertThat(
            new ObjectMapper()
                .readTree(created.snapshot().body())
                .path("accommodation")
                .path("sequenceNo")
                .asInt())
        .isEqualTo(2);

    JdbcAccommodationStore replayStore =
        new JdbcAccommodationStore(
            replayJdbc(created.snapshot(), "a".repeat(64)),
            throwingCoordinator(TripException.versionConflict()));
    var replay = replayStore.create(createRecord("a".repeat(64)));
    assertThat(replay.replaySnapshot().body()).isEqualTo(created.snapshot().body());
  }

  @Test
  void PATCH_date_reorder는_post_compaction_canonical_sequence를_반환한다() throws Exception {
    JdbcAccommodationStore store =
        new JdbcAccommodationStore(
            jdbcWithRows(
                List.of(
                    new AccommodationRow(
                        ACCOMMODATION,
                        "이동 숙소",
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-02"),
                        1),
                    new AccommodationRow(
                        EXISTING,
                        "선행 숙소",
                        LocalDate.parse("2026-09-02"),
                        LocalDate.parse("2026-09-03"),
                        2))),
            applyingCoordinator(state(null)));
    PatchAccommodationCommand command =
        new PatchAccommodationCommand(
            AccommodationPatchValue.omitted(),
            AccommodationPatchValue.omitted(),
            AccommodationPatchValue.present(LocalDate.parse("2026-09-03")),
            AccommodationPatchValue.present(LocalDate.parse("2026-09-04")),
            AccommodationPatchValue.omitted(),
            AccommodationPatchValue.omitted());

    var mutation =
        store.patch(new AccommodationPatchRecord(OWNER, TRIP, ACCOMMODATION, 1, command, NOW));

    assertThat(mutation.accommodation().sequenceNo()).isEqualTo(2);
  }

  private static void assertTranslated(TripException common, String expected) {
    AccommodationDeleteRecord record =
        new AccommodationDeleteRecord(OWNER, TRIP, ACCOMMODATION, 7, NOW);

    assertThatThrownBy(
            () ->
                new JdbcAccommodationStore(mock(JdbcTemplate.class), throwingCoordinator(common))
                    .delete(record))
        .isInstanceOf(AccommodationException.class)
        .extracting(failure -> ((AccommodationException) failure).code())
        .isEqualTo(expected);
  }

  private static AccommodationCreateRecord createRecord() {
    return createRecord("a".repeat(64));
  }

  private static AccommodationCreateRecord createRecord(String requestHash) {
    return new AccommodationCreateRecord(
        OWNER,
        TRIP,
        "replay-key",
        requestHash,
        1,
        ACCOMMODATION,
        new CreateAccommodationCommand(
            null,
            "숙소",
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-09-02"),
            LocalTime.parse("15:00"),
            LocalTime.parse("11:00")),
        NOW);
  }

  private static CreateAccommodationCommand create(
      String name, LocalDate checkIn, LocalDate checkOut) {
    return new CreateAccommodationCommand(
        null, name, checkIn, checkOut, LocalTime.parse("15:00"), LocalTime.parse("11:00"));
  }

  private static TripAggregateMutationState state(UUID active) {
    return new TripAggregateMutationState(
        "draft",
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-05"),
        "Asia/Seoul",
        active,
        1,
        active != null);
  }

  private static TripAggregateMutationCoordinator applyingCoordinator(
      TripAggregateMutationState state) {
    return new TripAggregateMutationCoordinator() {
      @Override
      public <T> TripAggregateMutationCommit<T> execute(
          UUID ownerId,
          UUID tripId,
          long expectedRevision,
          Instant updatedAt,
          TripAggregateMutationOperation<T> operation) {
        TripAggregateMutationPlan<T> plan = operation.apply(state);
        if (plan.scheduleEffect() != TripScheduleEffect.NONE) {
          plan.beforeRootEffect().apply();
          plan.effect().apply();
        }
        boolean invalidated = plan.scheduleEffect() == TripScheduleEffect.INVALIDATE;
        return new TripAggregateMutationCommit<>(
            plan.payload(),
            plan.scheduleEffect() == TripScheduleEffect.NONE
                ? state.revision()
                : state.revision() + 1,
            invalidated ? "draft" : state.status(),
            invalidated ? null : state.activeScheduleVersionId(),
            plan.scheduleEffect() == TripScheduleEffect.NONE
                ? "none"
                : invalidated ? "invalidated" : "maintained",
            invalidated,
            "\"trip-" + tripId + "-r" + (state.revision() + 1) + "\"");
      }

      @Override
      public <T> TripAggregateMutationCommit<T> executeMonotonic(
          UUID ownerId,
          UUID tripId,
          long expectedRevision,
          Instant requestedAt,
          com.timingjeju.api.application.trip.TripAggregateTimestampedMutationOperation<T>
              operation) {
        return execute(
            ownerId,
            tripId,
            expectedRevision,
            requestedAt,
            state -> operation.apply(state, requestedAt));
      }
    };
  }

  private static TripAggregateMutationCoordinator throwingCoordinator(TripException failure) {
    return new TripAggregateMutationCoordinator() {
      @Override
      public <T> TripAggregateMutationCommit<T> execute(
          UUID ownerId,
          UUID tripId,
          long expectedRevision,
          Instant updatedAt,
          TripAggregateMutationOperation<T> operation) {
        throw failure;
      }

      @Override
      public <T> TripAggregateMutationCommit<T> executeMonotonic(
          UUID ownerId,
          UUID tripId,
          long expectedRevision,
          Instant requestedAt,
          com.timingjeju.api.application.trip.TripAggregateTimestampedMutationOperation<T>
              operation) {
        throw failure;
      }
    };
  }

  private static JdbcTemplate jdbcWithRows(List<AccommodationRow> rows) throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("from public.accommodation_idempotency")) {
                return List.of();
              }
              if (sql.contains("from public.trip_accommodations")) {
                RowMapper<?> mapper = invocation.getArgument(1);
                List<Object> projected = new ArrayList<>();
                for (int index = 0; index < rows.size(); index++) {
                  projected.add(mapper.mapRow(resultSet(rows.get(index)), index));
                }
                return projected;
              }
              throw new AssertionError("예상하지 않은 query: " + sql);
            });
    return jdbc;
  }

  private static JdbcTemplate replayJdbc(AccommodationHttpSnapshot snapshot, String requestHash)
      throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    ResultSet result = mock(ResultSet.class);
    when(result.getBytes("response_body")).thenReturn(snapshot.body());
    when(result.getInt("response_status")).thenReturn(snapshot.status());
    when(result.getString("response_content_type")).thenReturn(snapshot.contentType());
    when(result.getString("response_location")).thenReturn(snapshot.location());
    when(result.getString("response_etag")).thenReturn(snapshot.etag());
    when(result.getString("request_hash")).thenReturn(requestHash);
    when(jdbc.query(
            contains("from public.accommodation_idempotency"),
            any(RowMapper.class),
            any(Object[].class)))
        .thenAnswer(
            invocation -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(result, 0)));
    return jdbc;
  }

  private static ResultSet resultSet(AccommodationRow row) throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.getObject("id", UUID.class)).thenReturn(row.id());
    when(result.getObject("place_id", UUID.class)).thenReturn(null);
    when(result.getString("custom_name")).thenReturn(row.name());
    when(result.getString("name")).thenReturn(row.name());
    when(result.getDate("check_in_date")).thenReturn(java.sql.Date.valueOf(row.checkIn()));
    when(result.getDate("check_out_date")).thenReturn(java.sql.Date.valueOf(row.checkOut()));
    when(result.getTime("check_in_time")).thenReturn(java.sql.Time.valueOf("15:00:00"));
    when(result.getTime("check_out_time")).thenReturn(java.sql.Time.valueOf("11:00:00"));
    when(result.getInt("sequence_no")).thenReturn(row.sequence());
    when(result.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(result.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    return result;
  }

  private record AccommodationRow(
      UUID id, String name, LocalDate checkIn, LocalDate checkOut, int sequence) {}
}
