package com.timingjeju.api.global.generation;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.generation.GenerationTripInputRepository;
import com.timingjeju.api.application.generation.GenerationTripSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcGenerationTripInputRepository implements GenerationTripInputRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcGenerationTripInputRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void save(GenerationTripSnapshot snapshot) {
    var input = snapshot.input();
    try {
      jdbc.update(
          """
          insert into timing_jeju_planner_private.generation_trip_inputs
          (run_id,trip_plan_id,owner_user_id,trip_revision,target_day_id,base_schedule_version_id,
           schema_version,structured_input,input_hash)
          values (?,?,?,?,?,?,1,?::jsonb,?)
          """,
          snapshot.runId(),
          input.tripId(),
          snapshot.ownerId(),
          input.tripRevision(),
          input.boundary().dayId(),
          input.baseScheduleVersionId(),
          snapshot.canonicalInput(),
          snapshot.inputHash());
    } catch (DataAccessException failure) {
      throw GenerationException.inputUnavailable();
    }
  }

  @Override
  public Optional<GenerationTripSnapshot> find(UUID runId) {
    try {
      return jdbc
          .query(
              """
          select run_id,owner_user_id,structured_input::text as structured_input,input_hash
          from timing_jeju_planner_private.generation_trip_inputs where run_id=?
          """,
              (row, index) ->
                  GenerationTripSnapshot.restore(
                      row.getObject("run_id", UUID.class),
                      row.getObject("owner_user_id", UUID.class),
                      row.getString("structured_input"),
                      row.getString("input_hash"),
                      mapper),
              runId)
          .stream()
          .findFirst();
    } catch (DataAccessException | GenerationException failure) {
      throw GenerationException.inputUnavailable();
    }
  }
}
