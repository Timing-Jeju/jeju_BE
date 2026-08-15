package com.timingjeju.api.global.tourapi.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.reference.ReferenceCode;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeLineage;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeRepository;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncException;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeUpsertCommand;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcReferenceCodeRepositoryIntegrationTest {

  private static final UUID RUN = UUID.fromString("25000000-0000-0000-0000-000000000001");
  private static final UUID SNAPSHOT = UUID.fromString("25000000-0000-0000-0000-000000000002");
  private static final String HASH =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

  @Autowired private ReferenceCodeRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clean();
    insertRunAndSnapshot(RUN, SNAPSHOT, "areaCode2", HASH);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void 같은_snapshot을_재실행해도_행과_값과_provenance가_변하지_않는다() {
    ReferenceCodeUpsertCommand command = command(SNAPSHOT, RUN, HASH, "제주특별자치도");

    var first = repository.upsert(command);
    UUID firstId =
        jdbcTemplate.queryForObject(
            "select id from public.external_reference_codes where external_code='50'", UUID.class);
    Timestamp firstUpdatedAt =
        jdbcTemplate.queryForObject(
            "select updated_at from public.external_reference_codes where id=?",
            Timestamp.class,
            firstId);
    var replay = repository.upsert(command);

    assertThat(first.inserted()).isEqualTo(1);
    assertThat(replay.skipped()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_reference_codes", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select id from public.external_reference_codes where external_code='50'",
                UUID.class))
        .isEqualTo(firstId);
    assertThat(
            jdbcTemplate.queryForObject(
                "select updated_at from public.external_reference_codes where id=?",
                Timestamp.class,
                firstId))
        .isEqualTo(firstUpdatedAt);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 같은_snapshot으로_값을_바꾸려하면_batch와_provenance를_원자적으로_rollback한다() {
    repository.upsert(command(SNAPSHOT, RUN, HASH, "제주특별자치도"));

    assertThatThrownBy(() -> repository.upsert(command(SNAPSHOT, RUN, HASH, "변조된 이름")))
        .isInstanceOf(ReferenceCodeSyncException.class)
        .hasMessageNotContaining("변조된 이름");

    assertThat(
            jdbcTemplate.queryForObject(
                "select code_name from public.external_reference_codes where external_code='50'",
                String.class))
        .isEqualTo("제주특별자치도");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 겹치는_유효기간은_저장하지_않는다() {
    repository.upsert(command(SNAPSHOT, RUN, HASH, "제주특별자치도"));
    jdbcTemplate.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(1)),
        RUN);
    UUID nextRun = UUID.fromString("25000000-0000-0000-0000-000000000003");
    UUID nextSnapshot = UUID.fromString("25000000-0000-0000-0000-000000000004");
    String nextHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    insertRunAndSnapshot(nextRun, nextSnapshot, "areaCode2", nextHash);
    ReferenceCodeUpsertCommand overlapping =
        new ReferenceCodeUpsertCommand(
            List.of(code("제주특별자치도")),
            LocalDate.of(2027, 1, 1),
            null,
            NOW,
            new ReferenceCodeLineage("areaCode2", nextHash, nextSnapshot, nextRun));

    assertThatThrownBy(() -> repository.upsert(overlapping))
        .isInstanceOf(ReferenceCodeSyncException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_reference_codes", Integer.class))
        .isEqualTo(1);
  }

  private ReferenceCodeUpsertCommand command(
      UUID snapshot, UUID run, String fingerprint, String name) {
    return new ReferenceCodeUpsertCommand(
        List.of(code(name)),
        LocalDate.of(2026, 1, 12),
        null,
        NOW,
        new ReferenceCodeLineage("areaCode2", fingerprint, snapshot, run));
  }

  private ReferenceCode code(String name) {
    return new ReferenceCode("ldong-region", "50", null, name, name, Map.of());
  }

  private void insertRunAndSnapshot(UUID run, UUID snapshot, String operation, String hash) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, 'tour_api', 'fixture', ?, '2026', 'running', ?, 'reference-v1',
                  'schema-v1', 'full', 'jeju', ?, ?, 'tour-api', 'KorService2')
        """,
        run,
        operation,
        Timestamp.from(NOW),
        hash,
        "issue-25-" + run);
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'tour-api', 'KorService2', ?, 'jeju', ?, '', ?, 'reference-v1', ?,
                  '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        operation,
        hash,
        Timestamp.from(NOW),
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        Timestamp.from(NOW));
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tour_api_operation_provenance");
    jdbcTemplate.update("delete from public.external_reference_codes");
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
  }
}
