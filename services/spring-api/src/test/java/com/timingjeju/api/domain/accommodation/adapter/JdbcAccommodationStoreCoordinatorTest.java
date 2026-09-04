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
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationOperation;
import com.timingjeju.api.application.trip.TripException;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

@Tag("unit")
@SuppressWarnings("unchecked")
class JdbcAccommodationStoreCoordinatorTest {
  private static final UUID OWNER = UUID.fromString("68000000-0000-0000-0000-000000000911");
  private static final UUID TRIP = UUID.fromString("68000000-0000-0000-0000-000000000912");
  private static final UUID ACCOMMODATION = UUID.fromString("68000000-0000-0000-0000-000000000913");
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

  private static void assertTranslated(TripException common, String expected) {
    TripAggregateMutationCoordinator coordinator =
        new TripAggregateMutationCoordinator() {
          @Override
          public <T> TripAggregateMutationCommit<T> execute(
              UUID ownerId,
              UUID tripId,
              long expectedRevision,
              Instant updatedAt,
              TripAggregateMutationOperation<T> operation) {
            throw common;
          }
        };
    AccommodationDeleteRecord record =
        new AccommodationDeleteRecord(OWNER, TRIP, ACCOMMODATION, 7, NOW);

    assertThatThrownBy(
            () -> new JdbcAccommodationStore(mock(JdbcTemplate.class), coordinator).delete(record))
        .isInstanceOf(AccommodationException.class)
        .extracting(failure -> ((AccommodationException) failure).code())
        .isEqualTo(expected);
  }

  private static AccommodationCreateRecord createRecord() {
    return new AccommodationCreateRecord(
        OWNER,
        TRIP,
        "replay-key",
        "a".repeat(64),
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
}
