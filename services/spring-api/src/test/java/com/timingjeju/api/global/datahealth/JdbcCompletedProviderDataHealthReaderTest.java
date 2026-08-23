package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthCatalog;
import com.timingjeju.api.application.datahealth.ProviderDataHealthException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("unit")
class JdbcCompletedProviderDataHealthReaderTest {

  @Test
  void canonical_allowlist는_완료된_TourAPI_TAGO_KMA_8개_operation으로_닫혀있다() {
    assertThat(CompletedProviderDataHealthCatalog.keys())
        .extracting(key -> key.provider() + "/" + key.service() + "/" + key.operation())
        .containsExactly(
            "TAGO/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList",
            "kma/VilageFcstInfoService_2.0/getUltraSrtFcst",
            "kma/VilageFcstInfoService_2.0/getUltraSrtNcst",
            "kma/VilageFcstInfoService_2.0/getVilageFcst",
            "tour-api/KorService2/areaBasedSyncList2",
            "tour-api/KorService2/locationBasedList2",
            "tour-api/KorService2/searchKeyword2",
            "tour-api/KorService2/searchStay2");
  }

  @Test
  void SQL은_allowlist_operation별_recent_terminal_32개안에서_latest와_valid_facts를_읽는다() {
    String sql = JdbcCompletedProviderDataHealthReader.SELECT_HEALTH;

    assertThat(sql)
        .contains(
            "with canonical(provider, service, operation) as",
            "left join lateral",
            "order by import_run.started_at desc, import_run.id desc",
            "limit 32",
            "status in ('succeeded', 'failed', 'partial', 'cancelled')",
            "import_run.idempotency_key is not null",
            "import_run.idempotency_enforced",
            "import_run.running_scope_enforced",
            "snapshot.parse_status = 'parsed'",
            "snapshot.import_run_id = recent.id")
        .doesNotContain(
            "raw_payload",
            "request_metadata_redacted",
            "metadata",
            "error_message",
            "scope_key",
            "request_fingerprint",
            "select import_run.idempotency_key",
            "import_run.idempotency_key as");
    assertThat(sql.indexOf("limit 32")).isLessThan(sql.indexOf("external_api_snapshots"));
    assertThat(sql).doesNotContain("limit 33");

    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());
    JdbcCompletedProviderDataHealthReader reader = new JdbcCompletedProviderDataHealthReader(jdbc);

    reader.read(CompletedProviderDataHealthCatalog.keys());

    verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
  }

  @Test
  void data_access_failure는_raw_SQL과_cause없는_stable_DATA_HEALTH_UNAVAILABLE다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(new DataAccessResourceFailureException("select token from private_table"));

    assertThatThrownBy(
            () ->
                new JdbcCompletedProviderDataHealthReader(jdbc)
                    .read(CompletedProviderDataHealthCatalog.keys()))
        .isInstanceOfSatisfying(
            ProviderDataHealthException.class,
            failure -> {
              assertThat(failure.code())
                  .isEqualTo(ProviderDataHealthException.Code.DATA_HEALTH_UNAVAILABLE);
              assertThat(failure.getMessage()).isEqualTo("DATA_HEALTH_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getMessage()).doesNotContain("select", "token", "private_table");
            });
  }

  @Test
  void programmer_bug는_DATA_HEALTH_UNAVAILABLE로_숨기지_않는다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    IllegalStateException programmerBug = new IllegalStateException("mapper bug");
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(programmerBug);

    assertThatThrownBy(
            () ->
                new JdbcCompletedProviderDataHealthReader(jdbc)
                    .read(CompletedProviderDataHealthCatalog.keys()))
        .isSameAs(programmerBug);
  }

  @Test
  void reader는_canonical_catalog의_subset만_허용하고_mobility를_query하지_않는다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    assertThatThrownBy(
            () ->
                new JdbcCompletedProviderDataHealthReader(jdbc)
                    .read(
                        List.of(
                            new com.timingjeju.api.application.datahealth.ProviderDataHealthKey(
                                "tmap", "routes", "transitRoute"))))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(jdbc);
  }

  @Test
  void recent_32_candidate안에서만_same_lineage_parsed_snapshot을_선택한다() {
    assertThat(JdbcCompletedProviderDataHealthReader.SELECT_HEALTH)
        .contains(
            "with recent as materialized",
            "enriched as materialized",
            "snapshot.import_run_id = recent.id",
            "snapshot.source_provider = recent.source_provider",
            "snapshot.source_service = recent.source_service",
            "snapshot.source_operation = recent.source_operation",
            "snapshot.parse_status = 'parsed'")
        .containsSubsequence("limit 32", "from public.external_api_snapshots snapshot");
  }

  @Test
  void whitespace나_oversize_key_mapping은_raw_cause없는_DATA_HEALTH_UNAVAILABLE다() throws Exception {
    ResultSet whitespace = mappedRow(" tour-api", "KorService2", "areaBasedSyncList2");
    ResultSet oversize = mappedRow("x".repeat(129), "KorService2", "areaBasedSyncList2");

    assertUnavailable(() -> JdbcCompletedProviderDataHealthReader.mapRow(whitespace, 0));
    assertUnavailable(() -> JdbcCompletedProviderDataHealthReader.mapRow(oversize, 0));
  }

  @Test
  void corrupt_time_shape_mapping도_raw_cause없는_DATA_HEALTH_UNAVAILABLE다() throws Exception {
    ResultSet resultSet = mappedRow("tour-api", "KorService2", "areaBasedSyncList2");
    when(resultSet.getString("latest_status")).thenReturn("failed");
    when(resultSet.getTimestamp("last_success_at"))
        .thenReturn(Timestamp.from(java.time.Instant.parse("2026-08-24T11:00:00Z")));
    when(resultSet.getTimestamp("facts_as_of")).thenReturn(null);

    assertUnavailable(() -> JdbcCompletedProviderDataHealthReader.mapRow(resultSet, 0));
  }

  private static ResultSet mappedRow(String provider, String service, String operation)
      throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("provider")).thenReturn(provider);
    when(resultSet.getString("service")).thenReturn(service);
    when(resultSet.getString("operation")).thenReturn(operation);
    when(resultSet.getTimestamp("last_attempt_at"))
        .thenReturn(Timestamp.from(java.time.Instant.parse("2026-08-24T12:00:00Z")));
    when(resultSet.getString("latest_status")).thenReturn("succeeded");
    when(resultSet.getTimestamp("last_success_at"))
        .thenReturn(Timestamp.from(java.time.Instant.parse("2026-08-24T12:00:00Z")));
    when(resultSet.getTimestamp("facts_as_of"))
        .thenReturn(Timestamp.from(java.time.Instant.parse("2026-08-24T11:59:00Z")));
    return resultSet;
  }

  private static void assertUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ProviderDataHealthException.class,
            failure -> {
              assertThat(failure.code())
                  .isEqualTo(ProviderDataHealthException.Code.DATA_HEALTH_UNAVAILABLE);
              assertThat(failure.getMessage()).isEqualTo("DATA_HEALTH_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
            });
  }
}
