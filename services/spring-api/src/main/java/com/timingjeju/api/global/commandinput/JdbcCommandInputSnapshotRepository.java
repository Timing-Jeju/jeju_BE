package com.timingjeju.api.global.commandinput;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputSnapshot;
import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.commandinput.CommandInputStorageException;
import com.timingjeju.api.application.commandinput.CommandLocationSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcCommandInputSnapshotRepository implements CommandInputSnapshotRepository {
  static final String PROJECTION =
      """
      compute_run_id, generation_run_id, schedule_revision_run_id,
      owner_user_id, trip_plan_id, base_schedule_version_id,
      run_type, schema_version, contract_version, algorithm_version,
      structured_input::text as structured_input, command_input_hash,
      location_supplied, coarse_location::text as coarse_location,
      location_precision_meters, location_policy_version,
      location_observed_at, location_expires_at
      """;

  static final String INSERT_SQL =
      """
      insert into public.compute_run_inputs (
        compute_run_id, generation_run_id, schedule_revision_run_id,
        owner_user_id, trip_plan_id, base_schedule_version_id,
        run_type, schema_version, contract_version, algorithm_version,
        structured_input, command_input_hash,
        location_supplied, coarse_location, location_precision_meters,
        location_policy_version, location_observed_at, location_expires_at
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?)
      returning
      """
          + PROJECTION;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final CommandInputCanonicalizer canonicalizer;

  public JdbcCommandInputSnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate은 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    this.canonicalizer = new CommandInputCanonicalizer(objectMapper);
  }

  @Override
  public CommandInputSnapshot save(CommandInputSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot은 필수입니다.");
    CommandLocationSnapshot location = snapshot.nullableLocation();
    try {
      List<CommandInputSnapshot> rows =
          jdbcTemplate.query(
              INSERT_SQL,
              (resultSet, rowNumber) -> map(resultSet),
              parentId(snapshot.parent(), CommandInputParent.Compute.class),
              parentId(snapshot.parent(), CommandInputParent.Generation.class),
              parentId(snapshot.parent(), CommandInputParent.ScheduleRevision.class),
              snapshot.ownerUserId(),
              snapshot.tripPlanId(),
              snapshot.baseScheduleVersionId(),
              snapshot.runType(),
              snapshot.schemaVersion(),
              snapshot.contractVersion(),
              snapshot.algorithmVersion(),
              snapshot.canonicalStructuredInput(),
              snapshot.commandInputHash(),
              location != null,
              location == null ? null : location.canonicalCoarseLocation(),
              location == null ? null : location.precisionMeters(),
              location == null ? null : location.policyVersion(),
              location == null ? null : Timestamp.from(location.observedAt()),
              location == null || location.expiresAt().isEmpty()
                  ? null
                  : Timestamp.from(location.expiresAt().orElseThrow()));
      return rows.stream().findFirst().orElseThrow(() -> rejected("COMMAND_INPUT_STORAGE_FAILURE"));
    } catch (CommandInputStorageException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw rejected("COMMAND_INPUT_REJECTED");
    }
  }

  @Override
  public Optional<CommandInputSnapshot> find(CommandInputParent parent) {
    Objects.requireNonNull(parent, "parent는 필수입니다.");
    String sql =
        "select "
            + PROJECTION
            + " from public.compute_run_inputs where "
            + parent.databaseColumn()
            + " = ?";
    try {
      return jdbcTemplate.query(sql, (resultSet, rowNumber) -> map(resultSet), parent.id()).stream()
          .findFirst();
    } catch (DataAccessException failure) {
      throw rejected("COMMAND_INPUT_STORAGE_FAILURE");
    }
  }

  private CommandInputSnapshot map(ResultSet resultSet) throws SQLException {
    try {
      String structured =
          canonicalizer.canonicalJson(
              objectMapper.readTree(resultSet.getString("structured_input")));
      boolean locationSupplied = resultSet.getBoolean("location_supplied");
      CommandLocationSnapshot location =
          locationSupplied
              ? new CommandLocationSnapshot(
                  canonicalizer.canonicalJson(
                      objectMapper.readTree(resultSet.getString("coarse_location"))),
                  integer(resultSet, "location_precision_meters"),
                  resultSet.getString("location_policy_version"),
                  instant(resultSet.getTimestamp("location_observed_at")),
                  instant(resultSet.getTimestamp("location_expires_at")))
              : null;
      return new CommandInputSnapshot(
          parent(resultSet),
          resultSet.getString("run_type"),
          resultSet.getInt("schema_version"),
          resultSet.getString("contract_version"),
          resultSet.getString("algorithm_version"),
          structured,
          resultSet.getString("command_input_hash"),
          resultSet.getObject("owner_user_id", UUID.class),
          resultSet.getObject("trip_plan_id", UUID.class),
          resultSet.getObject("base_schedule_version_id", UUID.class),
          location);
    } catch (RuntimeException failure) {
      throw rejected("COMMAND_INPUT_STORAGE_FAILURE");
    }
  }

  private static CommandInputParent parent(ResultSet resultSet) throws SQLException {
    UUID compute = resultSet.getObject("compute_run_id", UUID.class);
    UUID generation = resultSet.getObject("generation_run_id", UUID.class);
    UUID revision = resultSet.getObject("schedule_revision_run_id", UUID.class);
    if (compute != null) return new CommandInputParent.Compute(compute);
    if (generation != null) return new CommandInputParent.Generation(generation);
    if (revision != null) return new CommandInputParent.ScheduleRevision(revision);
    throw rejected("COMMAND_INPUT_STORAGE_FAILURE");
  }

  private static UUID parentId(CommandInputParent parent, Class<?> expectedType) {
    return expectedType.isInstance(parent) ? parent.id() : null;
  }

  private static Integer integer(ResultSet resultSet, String column) throws SQLException {
    int value = resultSet.getInt(column);
    return resultSet.wasNull() ? null : value;
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static CommandInputStorageException rejected(String code) {
    return new CommandInputStorageException(code);
  }
}
