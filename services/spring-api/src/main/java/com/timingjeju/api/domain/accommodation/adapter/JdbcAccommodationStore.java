package com.timingjeju.api.domain.accommodation.adapter;

import com.timingjeju.api.application.accommodation.Accommodation;
import com.timingjeju.api.application.accommodation.AccommodationCreateRecord;
import com.timingjeju.api.application.accommodation.AccommodationCreateStoreResult;
import com.timingjeju.api.application.accommodation.AccommodationDeleteRecord;
import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationHttpSnapshot;
import com.timingjeju.api.application.accommodation.AccommodationMutation;
import com.timingjeju.api.application.accommodation.AccommodationPatchRecord;
import com.timingjeju.api.application.accommodation.AccommodationPatchValue;
import com.timingjeju.api.application.accommodation.AccommodationStore;
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.accommodation.PatchAccommodationCommand;
import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripAggregateMutationState;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripRootPatch;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAccommodationStore implements AccommodationStore {
  private static final Comparator<Accommodation> CANONICAL_ORDER =
      Comparator.comparing(Accommodation::checkInDate)
          .thenComparing(Accommodation::checkOutDate)
          .thenComparing(Accommodation::accommodationId);

  private final JdbcTemplate jdbc;
  private final TripAggregateMutationCoordinator coordinator;

  public JdbcAccommodationStore(JdbcTemplate jdbc, TripAggregateMutationCoordinator coordinator) {
    this.jdbc = jdbc;
    this.coordinator = coordinator;
  }

  @Override
  @Transactional
  public AccommodationCreateStoreResult create(AccommodationCreateRecord record) {
    try {
      lockIdempotencyScope(record.ownerId(), record.tripId(), record.idempotencyKey());
      deleteExpiredMarker(record);
      IdempotencyRow previous = loadMarker(record);
      if (previous != null) {
        if (!previous.requestHash().equals(record.requestHash())) {
          throw AccommodationException.of("IDEMPOTENCY_KEY_REUSED");
        }
        if (previous.snapshot() == null) {
          throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
        }
        return AccommodationCreateStoreResult.replayed(previous.snapshot());
      }

      TripAggregateMutationCommit<Accommodation> commit =
          coordinator.execute(
              record.ownerId(),
              record.tripId(),
              record.expectedRevision(),
              record.now(),
              state -> createPlan(record, state));
      return AccommodationCreateStoreResult.created(mutation(record.tripId(), commit));
    } catch (TripException failure) {
      throw translate(failure);
    } catch (DataIntegrityViolationException failure) {
      throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
    } catch (DataAccessException failure) {
      throw AccommodationException.of("ACCOMMODATION_DATA_UNAVAILABLE");
    }
  }

  private TripAggregateMutationPlan<Accommodation> createPlan(
      AccommodationCreateRecord record, TripAggregateMutationState state) {
    String placeName = resolvePlaceName(record.command().placeId());
    Accommodation candidate = newAccommodation(record, placeName);
    List<Accommodation> desired = new ArrayList<>(load(record.tripId()));
    desired.add(candidate);
    validateAndSort(desired, state);
    Accommodation canonical =
        withSequence(candidate, indexOf(desired, candidate.accommodationId()) + 1);
    Runnable persist =
        () -> {
          jdbc.update(
              """
              insert into public.trip_accommodations (
                id, trip_plan_id, place_id, custom_name, check_in_date, check_out_date,
                check_in_time, check_out_time, sequence_no, source, created_at, updated_at
              ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'user_input', ?, ?)
              """,
              candidate.accommodationId(),
              record.tripId(),
              candidate.placeId(),
              candidate.customName(),
              Date.valueOf(candidate.checkInDate()),
              Date.valueOf(candidate.checkOutDate()),
              Time.valueOf(candidate.checkInTime()),
              Time.valueOf(candidate.checkOutTime()),
              desired.size(),
              Timestamp.from(record.now()),
              Timestamp.from(record.now()));
          compact(record.tripId(), desired);
        };
    if (state.activeScheduleVersionId() == null) {
      return TripAggregateMutationPlan.maintain(
          TripRootPatch.unchanged(),
          persist::run,
          () -> remember(record, canonical.accommodationId()),
          canonical);
    }
    return TripAggregateMutationPlan.invalidate(
        TripRootPatch.unchanged(),
        persist::run,
        () -> remember(record, canonical.accommodationId()),
        canonical);
  }

  @Override
  @Transactional
  public void completeCreateSnapshot(
      UUID ownerId, UUID tripId, String key, AccommodationHttpSnapshot snapshot) {
    try {
      int updated =
          jdbc.update(
              """
              update public.accommodation_idempotency
              set response_status = ?, response_content_type = ?, response_location = ?,
                  response_etag = ?, response_body = ?
              where owner_sub = ? and trip_plan_id = ? and idempotency_key = ?
                and response_body is null and expires_at > now()
              """,
              snapshot.status(),
              snapshot.contentType(),
              snapshot.location(),
              snapshot.etag(),
              snapshot.body(),
              ownerId,
              tripId,
              key);
      if (updated != 1) {
        throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
      }
    } catch (DataIntegrityViolationException failure) {
      throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
    } catch (DataAccessException failure) {
      throw AccommodationException.of("ACCOMMODATION_DATA_UNAVAILABLE");
    }
  }

  @Override
  @Transactional
  public AccommodationMutation patch(AccommodationPatchRecord record) {
    try {
      TripAggregateMutationCommit<Accommodation> commit =
          coordinator.execute(
              record.ownerId(),
              record.tripId(),
              record.expectedRevision(),
              record.now(),
              state -> patchPlan(record, state));
      return mutation(record.tripId(), commit);
    } catch (TripException failure) {
      throw translate(failure);
    } catch (DataIntegrityViolationException failure) {
      throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
    } catch (DataAccessException failure) {
      throw AccommodationException.of("ACCOMMODATION_DATA_UNAVAILABLE");
    }
  }

  private TripAggregateMutationPlan<Accommodation> patchPlan(
      AccommodationPatchRecord record, TripAggregateMutationState state) {
    List<Accommodation> current = new ArrayList<>(load(record.tripId()));
    int targetIndex = indexOf(current, record.accommodationId());
    if (targetIndex < 0) {
      throw AccommodationException.of("ACCOMMODATION_NOT_FOUND");
    }
    Accommodation before = current.get(targetIndex);
    Accommodation patched = apply(before, record.command(), record.now());
    String placeName = resolvePlaceName(patched.placeId());
    Accommodation desired = withName(patched, placeName == null ? patched.customName() : placeName);
    if (sameCanonical(before, desired)) {
      return TripAggregateMutationPlan.noChange(before);
    }
    current.set(targetIndex, desired);
    validateAndSort(current, state);
    Accommodation canonical =
        withSequence(desired, indexOf(current, desired.accommodationId()) + 1);
    Runnable persist =
        () -> {
          jdbc.update(
              """
              update public.trip_accommodations
              set place_id = ?, custom_name = ?, check_in_date = ?, check_out_date = ?,
                  check_in_time = ?, check_out_time = ?, updated_at = ?
              where id = ? and trip_plan_id = ?
              """,
              desired.placeId(),
              desired.customName(),
              Date.valueOf(desired.checkInDate()),
              Date.valueOf(desired.checkOutDate()),
              Time.valueOf(desired.checkInTime()),
              Time.valueOf(desired.checkOutTime()),
              Timestamp.from(record.now()),
              record.accommodationId(),
              record.tripId());
          compact(record.tripId(), current);
        };
    if (state.activeScheduleVersionId() == null) {
      return TripAggregateMutationPlan.maintain(TripRootPatch.unchanged(), persist::run, canonical);
    }
    return TripAggregateMutationPlan.invalidate(TripRootPatch.unchanged(), persist::run, canonical);
  }

  @Override
  @Transactional
  public void delete(AccommodationDeleteRecord record) {
    try {
      coordinator.execute(
          record.ownerId(),
          record.tripId(),
          record.expectedRevision(),
          record.now(),
          state -> deletePlan(record, state));
    } catch (TripException failure) {
      throw translate(failure);
    } catch (DataIntegrityViolationException failure) {
      throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
    } catch (DataAccessException failure) {
      throw AccommodationException.of("ACCOMMODATION_DATA_UNAVAILABLE");
    }
  }

  private TripAggregateMutationPlan<Void> deletePlan(
      AccommodationDeleteRecord record, TripAggregateMutationState state) {
    List<Accommodation> remaining = new ArrayList<>(load(record.tripId()));
    int targetIndex = indexOf(remaining, record.accommodationId());
    if (targetIndex < 0) {
      throw AccommodationException.of("ACCOMMODATION_NOT_FOUND");
    }
    if (state.activeScheduleVersionId() != null) {
      throw AccommodationException.of("ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE");
    }
    remaining.remove(targetIndex);
    validateAndSort(remaining, state);
    return TripAggregateMutationPlan.maintain(
        TripRootPatch.unchanged(),
        () -> {
          if (jdbc.update(
                  "delete from public.trip_accommodations where id = ? and trip_plan_id = ?",
                  record.accommodationId(),
                  record.tripId())
              != 1) {
            throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
          }
          compact(record.tripId(), remaining);
        },
        null);
  }

  private void lockIdempotencyScope(UUID ownerId, UUID tripId, String key) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        ignored -> {},
        ownerId + ":" + tripId + ":" + key);
  }

  private void deleteExpiredMarker(AccommodationCreateRecord record) {
    jdbc.update(
        """
        delete from public.accommodation_idempotency
        where owner_sub = ? and trip_plan_id = ? and idempotency_key = ? and expires_at <= now()
        """,
        record.ownerId(),
        record.tripId(),
        record.idempotencyKey());
  }

  private IdempotencyRow loadMarker(AccommodationCreateRecord record) {
    List<IdempotencyRow> rows =
        jdbc.query(
            """
            select request_hash, response_status, response_content_type, response_location,
                   response_etag, response_body
            from public.accommodation_idempotency
            where owner_sub = ? and trip_plan_id = ? and idempotency_key = ?
              and expires_at > now()
            for update
            """,
            (rs, row) -> {
              byte[] body = rs.getBytes("response_body");
              AccommodationHttpSnapshot snapshot =
                  body == null
                      ? null
                      : new AccommodationHttpSnapshot(
                          rs.getInt("response_status"),
                          rs.getString("response_content_type"),
                          rs.getString("response_location"),
                          rs.getString("response_etag"),
                          body);
              return new IdempotencyRow(rs.getString("request_hash"), snapshot);
            },
            record.ownerId(),
            record.tripId(),
            record.idempotencyKey());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void remember(AccommodationCreateRecord record, UUID accommodationId) {
    jdbc.update(
        """
        insert into public.accommodation_idempotency (
          owner_sub, trip_plan_id, idempotency_key, request_hash, accommodation_id,
          created_at, expires_at
        ) values (?, ?, ?, ?, ?, ?, ?)
        """,
        record.ownerId(),
        record.tripId(),
        record.idempotencyKey(),
        record.requestHash(),
        accommodationId,
        Timestamp.from(record.now()),
        Timestamp.from(record.now().plus(java.time.Duration.ofHours(24))));
  }

  private String resolvePlaceName(UUID placeId) {
    if (placeId == null) {
      return null;
    }
    List<String> names =
        jdbc.query(
            """
            select name
            from public.tour_places
            where id = ? and content_type_id = '32'
              and tombstoned_at is null and source_deleted_at is null
              and stale = false and (stale_at is null or stale_at > now())
            """,
            (rs, row) -> rs.getString("name"),
            placeId);
    if (names.isEmpty()) {
      throw AccommodationException.of("PLACE_NOT_FOUND");
    }
    return names.getFirst();
  }

  private Accommodation newAccommodation(AccommodationCreateRecord record, String placeName) {
    CreateAccommodationCommand command = record.command();
    return new Accommodation(
        record.accommodationId(),
        command.placeId(),
        command.customName(),
        placeName == null ? command.customName() : placeName,
        command.checkInDate(),
        command.checkOutDate(),
        command.checkInTime(),
        command.checkOutTime(),
        1,
        record.now(),
        record.now());
  }

  private List<Accommodation> load(UUID tripId) {
    return jdbc.query(
        """
        select accommodation.id, accommodation.place_id, accommodation.custom_name,
               coalesce(place.name, accommodation.custom_name) as name,
               accommodation.check_in_date, accommodation.check_out_date,
               accommodation.check_in_time, accommodation.check_out_time,
               accommodation.sequence_no, accommodation.created_at, accommodation.updated_at
        from public.trip_accommodations accommodation
        left join public.tour_places place on place.id = accommodation.place_id
        where accommodation.trip_plan_id = ?
        order by accommodation.check_in_date, accommodation.check_out_date, accommodation.id
        """,
        (rs, row) ->
            new Accommodation(
                rs.getObject("id", UUID.class),
                rs.getObject("place_id", UUID.class),
                rs.getString("custom_name"),
                rs.getString("name"),
                rs.getDate("check_in_date").toLocalDate(),
                rs.getDate("check_out_date").toLocalDate(),
                rs.getTime("check_in_time").toLocalTime(),
                rs.getTime("check_out_time").toLocalTime(),
                rs.getInt("sequence_no"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
        tripId);
  }

  private static void validateAndSort(
      List<Accommodation> values, TripAggregateMutationState state) {
    values.sort(CANONICAL_ORDER);
    for (int index = 0; index < values.size(); index++) {
      Accommodation current = values.get(index);
      if (!current.checkInDate().isBefore(current.checkOutDate())
          || current.checkInDate().isBefore(state.startDate())
          || current.checkOutDate().isAfter(state.endDate())
          || (index > 0 && !values.get(index - 1).checkOutDate().equals(current.checkInDate()))) {
        throw AccommodationException.of("ACCOMMODATION_DATE_GAP_OR_OVERLAP");
      }
    }
  }

  private void compact(UUID tripId, List<Accommodation> values) {
    jdbc.update(
        "update public.trip_accommodations set sequence_no = sequence_no + 1000000 where trip_plan_id = ?",
        tripId);
    for (int index = 0; index < values.size(); index++) {
      if (jdbc.update(
              "update public.trip_accommodations set sequence_no = ? where trip_plan_id = ? and id = ?",
              index + 1,
              tripId,
              values.get(index).accommodationId())
          != 1) {
        throw AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
      }
    }
  }

  private static AccommodationMutation mutation(
      UUID tripId, TripAggregateMutationCommit<Accommodation> commit) {
    boolean invalidated = "invalidated".equals(commit.scheduleEffect());
    return new AccommodationMutation(
        tripId,
        commit.payload(),
        invalidated ? "invalidated" : "none",
        invalidated,
        commit.activeScheduleVersionId(),
        commit.status(),
        commit.revision());
  }

  private static int indexOf(List<Accommodation> values, UUID accommodationId) {
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).accommodationId().equals(accommodationId)) {
        return index;
      }
    }
    return -1;
  }

  private static Accommodation apply(
      Accommodation current, PatchAccommodationCommand command, Instant now) {
    UUID placeId = value(command.placeId(), current.placeId());
    String customName = value(command.customName(), current.customName());
    if ((placeId == null) == (customName == null)) {
      throw AccommodationException.invalidRequest();
    }
    return new Accommodation(
        current.accommodationId(),
        placeId,
        customName,
        current.name(),
        value(command.checkInDate(), current.checkInDate()),
        value(command.checkOutDate(), current.checkOutDate()),
        value(command.checkInTime(), current.checkInTime()),
        value(command.checkOutTime(), current.checkOutTime()),
        current.sequenceNo(),
        current.createdAt(),
        now);
  }

  private static <T> T value(AccommodationPatchValue<T> patch, T current) {
    return patch.present() ? patch.value() : current;
  }

  private static Accommodation withName(Accommodation value, String name) {
    return new Accommodation(
        value.accommodationId(),
        value.placeId(),
        value.customName(),
        name,
        value.checkInDate(),
        value.checkOutDate(),
        value.checkInTime(),
        value.checkOutTime(),
        value.sequenceNo(),
        value.createdAt(),
        value.updatedAt());
  }

  private static Accommodation withSequence(Accommodation value, int sequenceNo) {
    return new Accommodation(
        value.accommodationId(),
        value.placeId(),
        value.customName(),
        value.name(),
        value.checkInDate(),
        value.checkOutDate(),
        value.checkInTime(),
        value.checkOutTime(),
        sequenceNo,
        value.createdAt(),
        value.updatedAt());
  }

  private static boolean sameCanonical(Accommodation left, Accommodation right) {
    return java.util.Objects.equals(left.placeId(), right.placeId())
        && java.util.Objects.equals(left.customName(), right.customName())
        && left.checkInDate().equals(right.checkInDate())
        && left.checkOutDate().equals(right.checkOutDate())
        && left.checkInTime().equals(right.checkInTime())
        && left.checkOutTime().equals(right.checkOutTime());
  }

  private static AccommodationException translate(TripException failure) {
    return switch (failure.code()) {
      case "TRIP_NOT_FOUND" -> AccommodationException.of("TRIP_NOT_FOUND");
      case "TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT" ->
          AccommodationException.of("TRIP_VERSION_CONFLICT");
      case "TRIP_CONSTRAINT_VIOLATION" ->
          AccommodationException.of("ACCOMMODATION_CONCURRENT_CONFLICT");
      default -> AccommodationException.of("ACCOMMODATION_DATA_UNAVAILABLE");
    };
  }

  private record IdempotencyRow(String requestHash, AccommodationHttpSnapshot snapshot) {}
}
