package com.timingjeju.api.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.retention.SnapshotRetentionCommand;
import com.timingjeju.api.application.retention.SnapshotRetentionException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("unit")
class JdbcSnapshotRetentionRepositoryTest {

  @Test
  void production_JDBC_생성자는_Spring_context에서_명시적으로_선택된다() {
    new ApplicationContextRunner()
        .withUserConfiguration(RepositoryContext.class)
        .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JdbcSnapshotRetentionRepository.class);
            });
  }

  @Test
  void candidate_SQL은_completed_provider_due_payload만_stable_order와_bound로_선택한다() {
    String sql = canonical(JdbcSnapshotRetentionRepository.CANDIDATE_SQL);

    assertThat(sql)
        .contains(
            "snapshot.purge_after <= :now",
            "snapshot.raw_payload is not null",
            "snapshot.purged_at is null",
            "snapshot.source_provider in (:providers)",
            "import_run.status <> 'running'",
            "order by snapshot.purge_after, snapshot.id",
            "limit :batchsize");
    assertThat(sql).doesNotContain("tmap", "select snapshot.raw_payload", "select *");
  }

  @Test
  void candidate_SQL은_snapshot과_import_run을_함께_nonblocking_lock한다() {
    assertThat(canonical(JdbcSnapshotRetentionRepository.CANDIDATE_SQL))
        .contains("for update of snapshot, import_run skip locked");
  }

  @Test
  void dryRun과_mutation은_동일한_candidate_CTE를_사용한다() {
    String candidate = canonical(JdbcSnapshotRetentionRepository.CANDIDATE_SQL);
    String dryRun = canonical(JdbcSnapshotRetentionRepository.DRY_RUN_SQL);
    String mutation = canonical(JdbcSnapshotRetentionRepository.MUTATION_SQL);

    assertThat(dryRun).startsWith(candidate).contains("select count(*) from candidates");
    assertThat(mutation).startsWith(candidate).contains("from candidates");
  }

  @Test
  void mutation은_payload와_purgedAt만_변경하고_row_lineage를_보존한다() {
    String sql = canonical(JdbcSnapshotRetentionRepository.MUTATION_SQL);
    String setClause = sql.substring(sql.indexOf(" set ") + 5, sql.indexOf(" from candidates"));

    assertThat(setClause).contains("raw_payload = null", "purged_at = :now");
    assertThat(setClause)
        .doesNotContain(
            "id =",
            "import_run_id =",
            "source_provider =",
            "source_service =",
            "source_operation =",
            "source_scope =",
            "request_hash =",
            "payload_hash =",
            "parser_version =",
            "parse_status =",
            "parsed_at =",
            "fetched_at =",
            "expires_at =",
            "purge_after =");
    assertThat(sql).doesNotContain("delete ", "truncate ", "returning snapshot.id");
  }

  @Test
  void SQL은_시각_batch_provider를_모두_bind하고_raw_payload를_projection하지_않는다() {
    String combined =
        canonical(
            JdbcSnapshotRetentionRepository.DRY_RUN_SQL
                + " "
                + JdbcSnapshotRetentionRepository.MUTATION_SQL);

    assertThat(combined).contains(":now", ":batchsize", ":providers");
    assertThat(combined)
        .doesNotContain(
            "select snapshot.raw_payload",
            "returning snapshot.raw_payload",
            "payload_hash as",
            "request_hash as",
            "source_scope as");
  }

  @Test
  void data_access_실패는_raw_cause없이_stable_domain_error로_변환한다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
        .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://secret raw sql"));
    JdbcSnapshotRetentionRepository repository =
        new JdbcSnapshotRetentionRepository(jdbc, () -> 0L);

    assertThatThrownBy(() -> repository.execute(command()))
        .isInstanceOf(SnapshotRetentionException.class)
        .hasMessage("SNAPSHOT_RETENTION_UNAVAILABLE")
        .hasNoCause();
  }

  @Test
  void programmer_exception은_domain_error로_숨기지_않는다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    IllegalStateException programmerFailure = new IllegalStateException("programmer bug");
    when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
        .thenThrow(programmerFailure);
    JdbcSnapshotRetentionRepository repository =
        new JdbcSnapshotRetentionRepository(jdbc, () -> 0L);

    assertThatThrownBy(() -> repository.execute(command())).isSameAs(programmerFailure);
  }

  @Test
  void shared_now는_exact_microsecond_Timestamp_한개로_bind한다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
        .thenReturn(1);
    JdbcSnapshotRetentionRepository repository =
        new JdbcSnapshotRetentionRepository(jdbc, () -> 0L);
    Instant microNow = Instant.parse("2026-08-24T12:00:00.123456Z");

    repository.execute(new SnapshotRetentionCommand(microNow, command().providers(), 1, true));

    ArgumentCaptor<MapSqlParameterSource> parameters =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    org.mockito.Mockito.verify(jdbc)
        .queryForObject(anyString(), parameters.capture(), eq(Integer.class));
    Object boundNow = parameters.getValue().getValue("now");
    assertThat(boundNow).isInstanceOf(Timestamp.class).isEqualTo(Timestamp.from(microNow));
    assertThat(((Timestamp) boundNow).getNanos()).isEqualTo(123_456_000);
    assertThat(parameters.getValue().getParameterNames())
        .containsOnly("now", "providers", "batchSize");
  }

  private static SnapshotRetentionCommand command() {
    return new SnapshotRetentionCommand(
        Instant.parse("2026-08-24T12:00:00Z"), List.of("TAGO", "kma", "tour-api"), 500, true);
  }

  private static String canonical(String sql) {
    return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  @Configuration(proxyBeanMethods = false)
  @Import(JdbcSnapshotRetentionRepository.class)
  static class RepositoryContext {}
}
