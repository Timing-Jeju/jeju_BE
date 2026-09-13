package com.timingjeju.api.domain.generation.adapter;

import com.timingjeju.api.application.commandinput.*;
import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.staypolicy.*;
import com.timingjeju.api.application.trip.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcGenerationIntakeStore implements GenerationIntakeStore {
  private final JdbcTemplate jdbc;
  private final TripStore trips;
  private final StayPolicyResolver stays;
  private final CommandInputSnapshotRepository commands;
  private final GenerationTripInputRepository inputs;
  private final GenerationPlaceResolver places;
  private final ObjectMapper mapper;
  private final boolean enabled;
  private final String airportId;

  public JdbcGenerationIntakeStore(
      JdbcTemplate jdbc,
      TripStore trips,
      StayPolicyResolver stays,
      CommandInputSnapshotRepository commands,
      GenerationTripInputRepository inputs,
      GenerationPlaceResolver places,
      ObjectMapper mapper,
      @Value("${app.schedule-generation.enabled:false}") boolean enabled,
      @Value("${app.schedule-generation.approved-airport-place-id:}") String airportId) {
    this.jdbc = jdbc;
    this.trips = trips;
    this.stays = stays;
    this.commands = commands;
    this.inputs = inputs;
    this.places = places;
    this.mapper = mapper;
    this.enabled = enabled;
    this.airportId = airportId;
  }

  @Override
  public void requireOwned(UUID ownerId, UUID tripId) {
    try {
      if (jdbc.queryForList(
              "select id from public.trip_plans where id=? and user_id=?",
              UUID.class,
              tripId,
              ownerId)
          .isEmpty()) throw TripException.notFound();
    } catch (DataAccessException failure) {
      throw GenerationException.intakeUnavailable();
    }
  }

  @Override
  @Transactional
  public GenerationAccepted accept(
      UUID ownerId, UUID tripId, long revision, CreateGenerationCommand command, Instant now) {
    try {
      // 저장 조건 writer와 동일한 Trip lock. 외부/MCP 호출은 이 transaction에 없다.
      if (jdbc.queryForList(
              "select id from public.trip_plans where id=? and user_id=? for update",
              UUID.class,
              tripId,
              ownerId)
          .isEmpty()) throw TripException.notFound();
      var trip = trips.findOwned(ownerId, tripId, now).orElseThrow(TripException::notFound);
      new GenerationAdmissionScope(
              ownerId,
              tripId,
              trip.revision(),
              trip.activeScheduleVersionId(),
              trip.days().stream().map(TripDay::dayId).toList())
          .validate(ownerId, tripId, revision, command);
      if (!enabled) throw GenerationException.intakeUnavailable();
      if (Set.of("completed", "cancelled", "failed").contains(trip.status()))
        throw GenerationException.inputConstraintViolation();
      int completed = 0;
      if (trip.activeScheduleVersionId() != null) {
        completed =
            jdbc.queryForObject(
                """
            select coalesce(coverage_through_day_no, ?) from public.trip_schedule_versions
            where id=? and trip_plan_id=? and status='active'
            """,
                Integer.class,
                trip.days().size(),
                trip.activeScheduleVersionId(),
                tripId);
      }
      UUID airport = resolveAirport();
      var subjects = new ArrayList<StayPolicySubject>();
      var placeIds = new TreeSet<UUID>();
      trip.placePreferences().forEach(p -> placeIds.add(p.placeId()));
      trip.plannerConditions().dayAnchors().forEach(a -> placeIds.add(a.lodgingPlaceId()));
      for (var id : placeIds) {
        var categories =
            jdbc.queryForList(
                """
            select category from public.tour_places where id=? and not stale
              and (stale_at is null or stale_at>?) and tombstoned_at is null
              and source_deleted_at is null for share
            """,
                String.class,
                id,
                Timestamp.from(now));
        if (categories.isEmpty()) throw GenerationException.inputConstraintViolation();
        subjects.add(new StayPolicySubject(id, categories.getFirst()));
      }
      var input =
          GenerationTripInput.capture(
              trip, command.targetDayId(), completed, airport, stays.resolveAll(subjects));
      var requiredPlaceIds = new HashSet<UUID>();
      requiredPlaceIds.add(input.boundary().startPlaceId());
      requiredPlaceIds.add(input.boundary().endPlaceId());
      input.places().forEach(place -> requiredPlaceIds.add(place.placeId()));
      // AI publication identity로 변환할 수 없는 장소는 worker queue 전에 거부한다.
      places.resolve(requiredPlaceIds, now);
      if (jdbc.queryForObject(
          """
          select exists(select 1 from public.itinerary_generation_runs
            where trip_plan_id=? and trip_day_id=? and status in ('queued','running'))
          """,
          Boolean.class,
          tripId,
          command.targetDayId())) throw GenerationException.activeRunConflict();
      var runId = UUID.randomUUID();
      var snapshot =
          new CommandInputCanonicalizer(mapper)
              .canonicalize(
                  new CommandInputRequest(
                      new CommandInputParent.Generation(runId),
                      "itinerary_generation",
                      2,
                      "0.7.0",
                      "generation-v1",
                      mapper
                          .createObjectNode()
                          .put("targetDayId", command.targetDayId().toString())
                          .put("candidateCount", 3)
                          .put("refreshExternalFacts", false),
                      ownerId,
                      tripId,
                      trip.activeScheduleVersionId()));
      jdbc.update(
          """
          insert into public.itinerary_generation_runs(id,trip_plan_id,trip_day_id,base_schedule_version_id,
            status,contract_version,algorithm_version,idempotency_key,requested_by_user_id,structured_input,created_at)
          values (?,?,?,?,'queued','0.7.0','generation-v1',?,?,?::jsonb,?)
          """,
          runId,
          tripId,
          command.targetDayId(),
          trip.activeScheduleVersionId(),
          runId.toString(),
          ownerId,
          snapshot.canonicalStructuredInput(),
          Timestamp.from(now));
      commands.save(snapshot);
      inputs.save(GenerationTripSnapshot.create(runId, ownerId, input, mapper));
      return new GenerationAccepted(
          "1.0.0",
          runId,
          "queued",
          "/api/v1/trips/" + tripId + "/schedule-generations/" + runId,
          snapshot.commandInputHash(),
          now.atOffset(ZoneOffset.ofHours(9)));
    } catch (DataAccessException | CommandInputStorageException failure) {
      throw GenerationException.intakeUnavailable();
    }
  }

  private UUID resolveAirport() {
    UUID id;
    try {
      id = UUID.fromString(airportId);
    } catch (IllegalArgumentException failure) {
      throw GenerationException.intakeUnavailable();
    }
    var found =
        jdbc.queryForList(
            """
        select id from public.tour_places where id=? and name='제주국제공항'
          and content_id is not null and not stale
          and exists(select 1 from public.data_import_runs r where r.id=tour_places.import_run_id
            and r.source_kind='tour_api' and r.status='succeeded')
          and (stale_at is null or stale_at>statement_timestamp())
          and tombstoned_at is null and source_deleted_at is null for share
        """,
            UUID.class,
            id);
    if (found.isEmpty()) throw GenerationException.intakeUnavailable();
    return id;
  }
}
