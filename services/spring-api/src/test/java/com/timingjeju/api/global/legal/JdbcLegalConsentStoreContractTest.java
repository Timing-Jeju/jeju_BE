package com.timingjeju.api.global.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.legal.ConsentDecision;
import com.timingjeju.api.application.legal.LegalDocument;
import com.timingjeju.api.application.legal.LegalProfileException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@Tag("unit")
class JdbcLegalConsentStoreContractTest {

  @Test
  void SQL은_user_profile_lock_latest_effective_exact_id_atomic_upsert와_필수검증을_고정한다() {
    String sql = JdbcLegalConsentStore.contractSql().toLowerCase(Locale.ROOT);
    String upsert = JdbcLegalConsentStore.upsertConsentSql().toLowerCase(Locale.ROOT);

    assertThat(sql)
        .contains("effective_at <= ?")
        .contains("retired_at is null or retired_at > ?")
        .contains("for update")
        .contains("on conflict (user_id, legal_document_id) do update")
        .contains(
            """
            values (
              ?::uuid,
              ?::uuid,
              ?::boolean,
              ?::timestamptz,
              case when ?::boolean then null::timestamptz else ?::timestamptz end,
              'web'
            )
            """)
        .contains("greatest(user_consents.agreed_at, excluded.agreed_at)")
        .contains("excluded.agreed_at > user_consents.agreed_at")
        .contains("legal_document_id in (%s)")
        .doesNotContain("jwt")
        .doesNotContain("ip_address");
    assertThat(upsert.split("\\?::", -1)).hasSize(7);
  }

  @Test
  @SuppressWarnings("unchecked")
  void active_document_평가시각은_profile_lock을_획득한_뒤에만_capture한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Clock clock = mock(Clock.class);
    UUID userId = UUID.fromString("19000000-0000-0000-0000-000000000001");
    UUID documentId = UUID.fromString("19200000-0000-0000-0000-000000000001");
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(userId))).thenReturn(List.of(userId));
    when(clock.instant()).thenReturn(Instant.parse("2026-08-25T00:00:00Z"));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
    JdbcLegalConsentStore store = new JdbcLegalConsentStore(jdbc, clock);

    assertThatThrownBy(
            () ->
                store.updateRequiredConsents(
                    userId,
                    "ko-KR",
                    List.of(new ConsentDecision(documentId, true)),
                    Instant.parse("2026-08-25T00:00:00Z")))
        .isInstanceOf(LegalProfileException.class);

    var ordered = inOrder(jdbc, clock);
    ordered.verify(jdbc).queryForList(anyString(), eq(UUID.class), eq(userId));
    ordered.verify(clock).instant();
  }

  @Test
  @SuppressWarnings("unchecked")
  void candidate_query는_Instant를_JDBC경계밖에서_Timestamp로_변환한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Clock clock = mock(Clock.class);
    Instant evaluatedAt = Instant.parse("2026-08-25T00:00:00Z");
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
    JdbcLegalConsentStore store = new JdbcLegalConsentStore(jdbc, clock);

    assertThat(store.findEffectiveCandidates("ko-KR", evaluatedAt)).isEmpty();

    Timestamp timestamp = Timestamp.from(evaluatedAt);
    verify(jdbc)
        .query(anyString(), any(RowMapper.class), eq("ko-KR"), eq(timestamp), eq(timestamp));
  }

  @Test
  @SuppressWarnings("unchecked")
  void consent_write는_PostgreSQL호환_UUID와_Timestamp만_순서대로_binding한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Clock clock = mock(Clock.class);
    UUID userId = UUID.fromString("19000000-0000-0000-0000-000000000001");
    UUID documentId = UUID.fromString("19200000-0000-0000-0000-000000000001");
    Instant evaluatedAt = Instant.parse("2026-08-25T00:00:00Z");
    LegalDocument document =
        new LegalDocument(
            documentId,
            "terms",
            "ko-KR",
            "1.0.0",
            "이용약관",
            "https://timing-jeju.example/legal/terms/1.0.0",
            true,
            evaluatedAt.minusSeconds(1));
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(userId))).thenReturn(List.of(userId));
    when(clock.instant()).thenReturn(evaluatedAt);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenReturn(List.of(document));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Timestamp.class), any(), any()))
        .thenReturn(Timestamp.from(evaluatedAt));
    JdbcLegalConsentStore store = new JdbcLegalConsentStore(jdbc, clock);

    assertThat(
            store.updateRequiredConsents(
                userId, "ko-KR", List.of(new ConsentDecision(documentId, true)), evaluatedAt))
        .extracting(result -> result.updatedAt())
        .isEqualTo(evaluatedAt);

    Timestamp timestamp = Timestamp.from(evaluatedAt);
    verify(jdbc)
        .update(
            anyString(),
            eq(userId),
            eq(documentId),
            eq(true),
            eq(timestamp),
            eq(true),
            eq(timestamp));
    verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq(userId), eq(documentId));
    verify(jdbc).queryForObject(anyString(), eq(Timestamp.class), eq(userId), eq(documentId));
  }
}
