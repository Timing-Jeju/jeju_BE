package com.timingjeju.api.domain.generation.adapter;

import com.timingjeju.api.application.generation.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

/** 현재 활성 버전을 다시 따라가지 않고, 접수 snapshot이 고정한 버전만 조회한다. */
@Repository
public class JdbcGenerationDayHistoryRepository implements GenerationDayHistoryRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcGenerationDayHistoryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public List<GenerationSelectedDay> findPrevious(GenerationTripInput input) {
    int expected = input.boundary().dayNo() - 1;
    if (expected == 0) return List.of();
    List<GenerationSelectedDay> history;
    try {
      history =
          jdbc.query(
              """
          select r.history::text,r.evidence::text,
            timing_jeju_planner_private.generation_day_result_valid(r.history,r.evidence) as valid
          from timing_jeju_planner_private.generation_day_results r
          join public.trip_schedule_versions v on v.id=r.schedule_version_id and v.trip_plan_id=r.trip_plan_id
          where r.trip_plan_id=? and r.schedule_version_id=? and v.status in ('active','superseded')
          order by r.history->>'tripDate'
          """,
              (row, index) -> {
                if (!row.getBoolean("valid")) throw GenerationException.inputUnavailable();
                GenerationSelectedDay day =
                    mapper
                        .readerFor(GenerationSelectedDay.class)
                        .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                        .readValue(row.getString("history"));
                GenerationEvidence evidence =
                    mapper.readValue(row.getString("evidence"), GenerationEvidence.class);
                if (!evidence.facts().keySet().containsAll(day.evidenceFactIds()))
                  throw GenerationException.inputUnavailable();
                return day;
              },
              input.tripId(),
              input.baseScheduleVersionId());
    } catch (RuntimeException failure) {
      throw GenerationException.inputUnavailable();
    }
    input.validatePreviousDays(history);
    return List.copyOf(history);
  }
}
