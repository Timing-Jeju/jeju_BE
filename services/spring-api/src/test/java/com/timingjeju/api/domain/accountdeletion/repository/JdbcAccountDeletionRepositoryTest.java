package com.timingjeju.api.domain.accountdeletion.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionRecord;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatus;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@Tag("unit")
class JdbcAccountDeletionRepositoryTest {
  private static final UUID USER = UUID.fromString("61000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

  @Test
  void replay와_token조회는_암호문_상태_시간을_완전하게_매핑한다() throws Exception {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ResultSet row = row();
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(
            invocation -> List.of(((RowMapper<?>) invocation.getArgument(2)).mapRow(row, 0)));
    var repository = new JdbcAccountDeletionRepository(jdbc);

    AccountDeletionRecord replay = repository.findForReplay(USER, new byte[] {1}).orElseThrow();
    AccountDeletionRecord byToken = repository.findByTokenHash(new byte[] {3}).orElseThrow();

    assertThat(replay.id()).isEqualTo(byToken.id());
    assertThat(replay.status()).isEqualTo(AccountDeletionStatus.RUNNING);
    assertThat(replay.statusTokenExpiresAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(replay.nextRetryAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(replay.completedAt()).isNull();
  }

  @Test
  void insert는_모든_보호필드를_전달하고_0row를_실패로_닫는다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1, 0);
    var repository = new JdbcAccountDeletionRepository(jdbc);
    AccountDeletionRecord record = record();

    repository.insert(record);

    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(anyString(), parameters.capture());
    assertThat(parameters.getValue().getValue("profileId")).isEqualTo(USER);
    assertThat(parameters.getValue().getValue("subjectCiphertext")).isEqualTo("subject-ciphertext");
    assertThat(parameters.getValue().getValue("subjectFingerprint")).isEqualTo(new byte[32]);
    assertThat(parameters.getValue().getValue("requestedAt"))
        .isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    assertThat(parameters.getValue().getValue("status")).isEqualTo("queued");
    assertThatThrownBy(() -> repository.insert(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("account deletion request insert failed");
  }

  @Test
  void cleanup은_만료와_terminal_retention_경계에서_key_reference를_제한된_batch로_해제한다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(2, 0);
    var repository = new JdbcAccountDeletionRepository(jdbc);

    assertThat(repository.clearExpiredSecrets(NOW, Duration.ofHours(24), 50)).isEqualTo(2);
    assertThat(repository.clearExpiredSecrets(NOW, Duration.ofHours(24), 50)).isZero();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), parameters.capture());
    assertThat(sql.getValue())
        .contains("status_token_ciphertext = null", "status_token_key_version = null")
        .contains(
            "status_token_expires_at <= :now",
            "completed_at + (:terminalRetentionSeconds * interval '1 second') <= :now")
        .contains("limit :batchSize");
    assertThat(parameters.getValue().getValue("now")).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
  }

  private static ResultSet row() throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("id")).thenReturn("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    when(row.getObject("user_profile_id", UUID.class)).thenReturn(USER);
    when(row.getBytes("auth_subject_fingerprint")).thenReturn(new byte[32]);
    when(row.getBytes("idempotency_hash")).thenReturn(new byte[] {1});
    when(row.getBytes("request_hash")).thenReturn(new byte[] {2});
    when(row.getBytes("status_token_hash")).thenReturn(new byte[] {3});
    when(row.getString("status_token_ciphertext")).thenReturn("token-ciphertext");
    when(row.getString("status_token_key_version")).thenReturn("v1");
    when(row.getString("auth_subject_ciphertext")).thenReturn("subject-ciphertext");
    when(row.getString("auth_subject_key_version")).thenReturn("v1");
    when(row.getString("status")).thenReturn("running");
    when(row.getString("current_step")).thenReturn("PROFILE_IMAGES_DELETED");
    when(row.getObject("status_token_expires_at", OffsetDateTime.class))
        .thenReturn(NOW.plusSeconds(60).atOffset(ZoneOffset.UTC));
    when(row.getObject("next_retry_at", OffsetDateTime.class))
        .thenReturn(NOW.plusSeconds(30).atOffset(ZoneOffset.UTC));
    when(row.getObject("requested_at", OffsetDateTime.class))
        .thenReturn(NOW.atOffset(ZoneOffset.UTC));
    when(row.getObject("completed_at", OffsetDateTime.class)).thenReturn(null);
    return row;
  }

  private static AccountDeletionRecord record() {
    return new AccountDeletionRecord(
        "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        USER,
        new byte[32],
        new byte[] {1},
        new byte[] {2},
        new byte[] {3},
        "token-ciphertext",
        "v1",
        NOW.plusSeconds(60),
        "subject-ciphertext",
        "v1",
        AccountDeletionStatus.QUEUED,
        null,
        NOW,
        NOW,
        null);
  }
}
