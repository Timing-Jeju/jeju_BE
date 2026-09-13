package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAccountDeletionWorkRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@Tag("unit")
class JdbcAccountDeletionWorkRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
  private static final DeletionLease LEASE =
      new DeletionLease("01K4V106000000000000000001", "worker-106", 4, 2);

  @Test
  void claim은_queued와_retry_due_만료_running을_skip_locked로_잡고_attempt와_fence를_증가시킨다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(LEASE));
    JdbcAccountDeletionWorkRepository repository = new JdbcAccountDeletionWorkRepository(jdbc);

    assertThat(repository.claimAvailable("worker-106", NOW, Duration.ofSeconds(30), 50))
        .containsExactly(LEASE);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
    assertThat(sql.getValue().toLowerCase())
        .contains("status = 'queued'", "status = 'running'")
        .contains("lease_expires_at <= :now", "next_retry_at <= :now")
        .contains("for update skip locked")
        .contains("fencing_token = fencing_token + 1", "attempt = attempt + 1")
        .contains("returning");
    assertThat(parameters.getValue().getValue("owner")).isEqualTo("worker-106");
    assertThat(parameters.getValue().getValue("leaseExpiresAt")).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void load는_61의_ULID와_text_cipher를_완료_step에_매핑하고_profile_fk를_요구하지_않는다() throws Exception {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    java.sql.ResultSet first = row(DeletionStep.SESSIONS_REVOKED.name());
    java.sql.ResultSet second = row(DeletionStep.ACCOUNT_REQUESTS_DENIED.name());
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(2);
              return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
            });
    JdbcAccountDeletionWorkRepository repository = new JdbcAccountDeletionWorkRepository(jdbc);

    Optional<DeletionWork> loaded = repository.load(LEASE);

    assertThat(loaded).isPresent();
    assertThat(loaded.orElseThrow().requestId()).isEqualTo(LEASE.requestId());
    assertThat(loaded.orElseThrow().encryptedSubject().ciphertext()).isEqualTo("ciphertext-v1");
    assertThat(loaded.orElseThrow().completedSteps())
        .containsExactlyInAnyOrder(
            DeletionStep.SESSIONS_REVOKED, DeletionStep.ACCOUNT_REQUESTS_DENIED);
  }

  @Test
  void 모든_상태_변경은_request_owner_fence로_CAS하고_auth완료는_subject_clear와_단일문이다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    JdbcAccountDeletionWorkRepository repository = new JdbcAccountDeletionWorkRepository(jdbc);

    assertThat(repository.startStep(LEASE, DeletionStep.SESSIONS_REVOKED, NOW)).isTrue();
    assertThat(repository.completeStep(LEASE, DeletionStep.SESSIONS_REVOKED, NOW)).isTrue();
    assertThat(repository.completeAuthDeletionAndClearSubject(LEASE, NOW)).isTrue();
    assertThat(repository.heartbeat(LEASE, NOW, Duration.ofSeconds(30))).isTrue();
    assertThat(repository.retry(LEASE, "TEMPORARY", NOW.plusSeconds(1), NOW)).isTrue();
    assertThat(repository.fail(LEASE, "TERMINAL", NOW)).isTrue();
    assertThat(repository.succeed(LEASE, NOW)).isTrue();
    assertThat(repository.confirmCancelled(LEASE, NOW)).isTrue();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.times(8)).update(sql.capture(), any(SqlParameterSource.class));
    assertThat(sql.getAllValues())
        .allSatisfy(
            statement ->
                assertThat(statement.toLowerCase())
                    .contains("id = :requestid", "lease_owner = :owner", "fencing_token = :fence"));
    assertThat(sql.getAllValues().get(2).toLowerCase())
        .contains("auth_subject_ciphertext = null", "auth_subject_key_version = null")
        .contains("account_deletion_steps");
  }

  private static java.sql.ResultSet row(String step) throws Exception {
    java.sql.ResultSet resultSet = mock(java.sql.ResultSet.class);
    when(resultSet.getString("id")).thenReturn(LEASE.requestId());
    when(resultSet.getBoolean("cancellation_requested")).thenReturn(false);
    when(resultSet.getString("auth_subject_ciphertext")).thenReturn("ciphertext-v1");
    when(resultSet.getString("auth_subject_key_version")).thenReturn("key-v1");
    when(resultSet.getString("step")).thenReturn(step);
    return resultSet;
  }
}
