package com.timingjeju.api.global.commandinput;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputSnapshot;
import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.commandinput.CommandInputStorageException;
import java.sql.ResultSet;
import java.sql.SQLException;
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
      structured_input::text as structured_input, command_input_hash
      """;

  static final String INSERT_SQL =
      """
      insert into public.compute_run_inputs (
        compute_run_id, generation_run_id, schedule_revision_run_id,
        owner_user_id, trip_plan_id, base_schedule_version_id,
        run_type, schema_version, contract_version, algorithm_version,
        structured_input, command_input_hash
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
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
              snapshot.commandInputHash());
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
          resultSet.getObject("base_schedule_version_id", UUID.class));
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

  private static CommandInputStorageException rejected(String code) {
    return new CommandInputStorageException(code);
  }
}
