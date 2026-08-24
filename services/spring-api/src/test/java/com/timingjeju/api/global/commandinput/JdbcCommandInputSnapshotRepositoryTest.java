package com.timingjeju.api.global.commandinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputRequest;
import com.timingjeju.api.application.commandinput.CommandInputStorageException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class JdbcCommandInputSnapshotRepositoryTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void repository는_Spring_exception_translation_proxy가_생성할_수_있다() {
    assertThat(Modifier.isFinal(JdbcCommandInputSnapshotRepository.class.getModifiers())).isFalse();
  }

  @Test
  void insert는_snapshot_field만_bind하고_update나_MCP_hash를_소유하지_않는다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var snapshot = snapshot();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(snapshot));
    var repository = new JdbcCommandInputSnapshotRepository(jdbc, objectMapper);

    assertThat(repository.save(snapshot)).isEqualTo(snapshot);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
    assertThat(canonical(sql.getValue()))
        .contains("insert into public.compute_run_inputs", "returning")
        .doesNotContain(" update ", "mcp_input_hash", "mcp_compute_call_logs", "raw_request");
  }

  @Test
  void parent별_reader는_고정_column_allowlist를_사용하고_동적_ID만_bind한다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    var repository = new JdbcCommandInputSnapshotRepository(jdbc, objectMapper);

    for (CommandInputParent parent :
        List.of(
            new CommandInputParent.Compute(UUID.randomUUID()),
            new CommandInputParent.Generation(UUID.randomUUID()),
            new CommandInputParent.ScheduleRevision(UUID.randomUUID()))) {
      assertThat(repository.find(parent)).isEmpty();
    }

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.times(3))
        .query(sql.capture(), any(RowMapper.class), any(Object[].class));
    assertThat(sql.getAllValues())
        .allSatisfy(value -> assertThat(value).contains(" = ?").doesNotContain("select *"));
    assertThat(sql.getAllValues().stream().map(JdbcCommandInputSnapshotRepositoryTest::canonical))
        .anyMatch(value -> value.contains("compute_run_id = ?"))
        .anyMatch(value -> value.contains("generation_run_id = ?"))
        .anyMatch(value -> value.contains("schedule_revision_run_id = ?"));
  }

  @Test
  void DB_constraint_failure는_raw_SQL이나_payload_cause를_노출하지_않는_stable_error다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenThrow(new DataIntegrityViolationException("raw payload secret"));
    var repository = new JdbcCommandInputSnapshotRepository(jdbc, objectMapper);

    assertThatThrownBy(() -> repository.save(snapshot()))
        .isExactlyInstanceOf(CommandInputStorageException.class)
        .hasMessage("COMMAND_INPUT_REJECTED")
        .hasNoCause();
  }

  private com.timingjeju.api.application.commandinput.CommandInputSnapshot snapshot()
      throws Exception {
    var request =
        new CommandInputRequest(
            new CommandInputParent.Compute(UUID.fromString("10810000-0000-0000-0000-000000000001")),
            "feasibility",
            1,
            "command/v1",
            "algorithm/v1",
            objectMapper.readTree("{\"refreshExternalFacts\":false}"),
            UUID.fromString("10810000-0000-0000-0000-000000000002"),
            UUID.fromString("10810000-0000-0000-0000-000000000003"),
            UUID.fromString("10810000-0000-0000-0000-000000000004"),
            null);
    return new CommandInputCanonicalizer(objectMapper).canonicalize(request);
  }

  private static String canonical(String sql) {
    return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }
}
