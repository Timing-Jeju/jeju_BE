package com.timingjeju.api.domain.generation.adapter;

import com.timingjeju.api.application.generation.*;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 완료 transaction과 Trip 잠금 내부에서만 호출하는 후보 일정 writer. */
final class JdbcGenerationCandidateWriter {
  private final JdbcTemplate jdbc;
  private final tools.jackson.databind.ObjectMapper mapper =
      tools.jackson.databind.json.JsonMapper.builder().build();

  JdbcGenerationCandidateWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void write(
      UUID runId,
      GenerationTripSnapshot snapshot,
      GenerationCandidateProjection.Candidate candidate,
      GenerationEvidence evidence) {
    var input = snapshot.input();
    var day = candidate.scheduleDay();
    if (!day.dayId().equals(input.boundary().dayId())) throw GenerationException.invalidResult();
    int coverage = input.boundary().dayNo();
    if (input.baseScheduleVersionId() != null) {
      var baseCoverage =
          jdbc.queryForObject(
              """
          select coalesce(coverage_through_day_no, ?) from public.trip_schedule_versions
          where id=? and trip_plan_id=? and status='active'
          """,
              Integer.class,
              input.days().size(),
              input.baseScheduleVersionId(),
              input.tripId());
      if (baseCoverage == null) throw GenerationException.invalidResult();
      coverage = Math.max(coverage, baseCoverage);
    }
    var version = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.trip_schedule_versions
          (id,trip_plan_id,version_no,base_schedule_version_id,status,source_type,
           created_by_user_id,coverage_through_day_no)
        select ?,?,coalesce(max(version_no),0)+1,?,'draft','ai_generation',?,?
        from public.trip_schedule_versions where trip_plan_id=?
        """,
        version,
        input.tripId(),
        input.baseScheduleVersionId(),
        snapshot.ownerId(),
        coverage,
        input.tripId());
    copyOtherDays(input, version);
    var ids = new HashMap<Integer, UUID>();
    for (var item : day.items()) {
      var id = UUID.randomUUID();
      if (ids.put(item.sequenceNo(), id) != null) throw GenerationException.invalidResult();
      boolean required =
          input.places().stream()
              .anyMatch(
                  place ->
                      place.placeId().equals(item.placeId())
                          && place.type().equals("must_visit")
                          && item.boundaryRole() == null);
      String title =
          "day_start".equals(item.boundaryRole())
              ? "일정 시작"
              : "day_end".equals(item.boundaryRole())
                  ? "일정 종료"
                  : jdbc.queryForObject(
                      "select name from public.tour_places where id=?",
                      String.class,
                      item.placeId());
      jdbc.update(
          """
          insert into public.trip_items
            (id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,title,
             planned_start_at,planned_end_at,stay_minutes,boundary_role,required,source)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,'ai_generated')
          """,
          id,
          input.tripId(),
          day.dayId(),
          version,
          item.sequenceNo(),
          item.itemType(),
          item.placeId(),
          title,
          Timestamp.from(item.startAt().toInstant()),
          Timestamp.from(item.endAt().toInstant()),
          item.stayMinutes(),
          item.boundaryRole(),
          required);
    }
    for (var connection : day.connections()) {
      var from = day.items().get(connection.fromSequenceNo() - 1);
      var transferEvent =
          connection.events().stream().filter(event -> event.type().equals("transfer")).findFirst();
      String mode = "walk";
      int walk = 0, interchange = 0, duration = 0, distance = 0, buffer = 0;
      Integer wait = 0, ride = 0;
      GenerationLegPrecision precision = null;
      Integer fare = 0;
      var departure = from.endAt();
      var arrival = departure;
      if (transferEvent.isPresent()) {
        var event = transferEvent.get();
        var transfer =
            candidate.transfers().stream()
                .filter(value -> value.eventId().equals(event.eventId()))
                .findFirst()
                .orElseThrow(GenerationException::invalidResult);
        mode = transfer.mode().equals("bus") ? "public_transit" : transfer.mode();
        departure = event.startAt();
        arrival = event.endAt();
        duration = event.durationMinutes();
        distance = transfer.distanceMeters();
        for (var segment : transfer.walks()) {
          if (segment.kind().equals("transfer_walk"))
            interchange = Math.addExact(interchange, segment.plannedMinutes());
          else walk = Math.addExact(walk, segment.plannedMinutes());
        }
        if (mode.equals("public_transit")) {
          precision = GenerationLegPrecision.from(event, transfer);
          ride = precision.rideMinutes();
          wait = precision.waitMinutes();
        } else {
          if (mode.equals("taxi")) ride = duration;
          wait = duration - walk - ride - interchange;
          if (wait < 0) throw GenerationException.invalidResult();
        }
        var range = transfer.fare();
        fare = range != null && range.minKrw() == range.maxKrw() ? range.minKrw() : null;
        for (var eventBuffer : connection.events())
          if (eventBuffer.type().equals("buffer"))
            buffer = Math.addExact(buffer, eventBuffer.durationMinutes());
      } else if (!from.placeId().equals(day.items().get(connection.toSequenceNo() - 1).placeId())) {
        throw GenerationException.invalidResult();
      }
      jdbc.update(
          """
          insert into public.trip_legs
            (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,
             transport_mode,planned_departure_at,planned_arrival_at,walk_minutes,wait_minutes,
             ride_minutes,transfer_minutes,duration_minutes,buffer_minutes,distance_meters,estimated_fare,facts)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
          """,
          input.tripId(),
          day.dayId(),
          version,
          connection.fromSequenceNo(),
          ids.get(connection.fromSequenceNo()),
          ids.get(connection.toSequenceNo()),
          mode,
          Timestamp.from(departure.toInstant()),
          Timestamp.from(arrival.toInstant()),
          walk,
          wait,
          ride,
          interchange,
          duration,
          buffer,
          distance,
          fare,
          connectionFacts(candidate, connection, precision));
    }
    if (!candidate.history().dayId().equals(day.dayId())
        || !evidence.facts().keySet().containsAll(candidate.history().evidenceFactIds()))
      throw GenerationException.invalidResult();
    jdbc.update(
        """
        insert into timing_jeju_planner_private.generation_day_results
          (schedule_version_id,trip_plan_id,trip_day_id,history,evidence)
        values (?,?,?,?::jsonb,?::jsonb)
        """,
        version,
        input.tripId(),
        day.dayId(),
        mapper.writeValueAsString(candidate.history()),
        mapper.writeValueAsString(evidence));
    jdbc.update("update public.trip_schedule_versions set status='candidate' where id=?", version);
    jdbc.update(
        """
        insert into public.itinerary_generation_candidates
          (trip_plan_id,generation_run_id,schedule_version_id,rank_no,score,strategy,feasibility,explanation,created_at,expires_at)
        values (?,?,?,?,?,?,?,?,statement_timestamp(),statement_timestamp()+interval '24 hours')
        """,
        input.tripId(),
        runId,
        version,
        candidate.rank(),
        candidate.score(),
        candidate.strategy(),
        candidate.feasibility(),
        candidate.explanation());
  }

  private String connectionFacts(
      GenerationCandidateProjection.Candidate candidate,
      GenerationScheduleDay.Connection connection,
      GenerationLegPrecision precision) {
    var eventIds =
        connection.events().stream()
            .map(GenerationTimeline.Event::eventId)
            .collect(java.util.stream.Collectors.toSet());
    // 0분 위치 연속성의 buffer_minutes는 0으로 유지하고 실제 계획 버퍼는 별도 보존한다.
    // 공개 trip_legs.facts에는 계획에 필요한 닫힌 비위치 projection만 저장한다.
    // 원본 event/transfer/risk record에는 시각과 외부 fact 식별자가 포함된다.
    var events =
        connection.events().stream()
            .map(
                event ->
                    java.util.Map.of(
                        "type", event.type(), "durationMinutes", event.durationMinutes()))
            .toList();
    var risks =
        candidate.timeline().risks().stream()
            .filter(value -> eventIds.contains(value.eventId()))
            .map(
                value ->
                    java.util.Map.of("level", value.level(), "reasonCodes", value.reasonCodes()))
            .toList();
    var generation =
        new java.util.HashMap<String, Object>(
            java.util.Map.of(
                "schemaVersion", 1,
                "events", events,
                "risks", risks));
    if (precision != null) generation.put("precision", precision);
    return mapper.writeValueAsString(java.util.Map.of("generation", generation));
  }

  private void copyOtherDays(GenerationTripInput input, UUID version) {
    if (input.baseScheduleVersionId() == null) return;
    jdbc.update(
        """
        insert into public.trip_items
          (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,title,
           planned_start_at,planned_end_at,stay_minutes,buffer_after_minutes,required,source,memo,facts,
           accommodation_id,transport_event_id,boundary_role)
        select trip_plan_id,trip_day_id,?,sequence_no,item_type,place_id,title,
          planned_start_at,planned_end_at,stay_minutes,buffer_after_minutes,required,source,memo,facts,
          accommodation_id,transport_event_id,boundary_role
        from public.trip_items where trip_plan_id=? and schedule_version_id=? and trip_day_id<>?
        """,
        version,
        input.tripId(),
        input.baseScheduleVersionId(),
        input.boundary().dayId());
    jdbc.update(
        """
        insert into public.trip_legs
          (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,
           transport_mode,origin_stop_id,destination_stop_id,route_id,planned_departure_at,planned_arrival_at,
           walk_minutes,wait_minutes,ride_minutes,transfer_minutes,duration_minutes,buffer_minutes,
           distance_meters,estimated_fare,risk_score,facts)
        select l.trip_plan_id,l.trip_day_id,?,l.sequence_no,nf.id,nt.id,
          l.transport_mode,l.origin_stop_id,l.destination_stop_id,l.route_id,l.planned_departure_at,l.planned_arrival_at,
          l.walk_minutes,l.wait_minutes,l.ride_minutes,l.transfer_minutes,l.duration_minutes,l.buffer_minutes,
          l.distance_meters,l.estimated_fare,l.risk_score,l.facts
        from public.trip_legs l
        join public.trip_items ofi on ofi.id=l.from_item_id
        join public.trip_items oti on oti.id=l.to_item_id
        join public.trip_items nf on nf.schedule_version_id=? and nf.trip_day_id=l.trip_day_id and nf.sequence_no=ofi.sequence_no
        join public.trip_items nt on nt.schedule_version_id=? and nt.trip_day_id=l.trip_day_id and nt.sequence_no=oti.sequence_no
        where l.trip_plan_id=? and l.schedule_version_id=? and l.trip_day_id<>?
        """,
        version,
        version,
        version,
        input.tripId(),
        input.baseScheduleVersionId(),
        input.boundary().dayId());
    // 기존 계획 경로가 있으면 같은 계획 항목 identity로 정규 clone한다.
    jdbc.update(
        """
        update public.trip_legs copied set mobility_route_snapshot_id=
          timing_jeju_planner_private.clone_planned_route_snapshot(
            old.mobility_route_snapshot_id, copied.trip_plan_id, copied.schedule_version_id,
            copied.from_item_id,copied.to_item_id,copied.planned_departure_at,statement_timestamp())
        from public.trip_legs old
        where copied.schedule_version_id=? and old.schedule_version_id=?
          and copied.trip_plan_id=old.trip_plan_id and copied.trip_day_id=old.trip_day_id
          and copied.sequence_no=old.sequence_no and old.mobility_route_snapshot_id is not null
        """,
        version,
        input.baseScheduleVersionId());
    jdbc.update(
        """
        insert into timing_jeju_planner_private.generation_day_results
          (schedule_version_id,trip_plan_id,trip_day_id,schema_version,history,evidence)
        select ?,trip_plan_id,trip_day_id,schema_version,history,evidence
        from timing_jeju_planner_private.generation_day_results
        where trip_plan_id=? and schedule_version_id=? and trip_day_id<>?
        """,
        version,
        input.tripId(),
        input.baseScheduleVersionId(),
        input.boundary().dayId());
  }
}
