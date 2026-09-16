package com.timingjeju.api.domain.accountdeletion.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@Tag("unit")
class JdbcRecentAuthSessionGatewayTest {
  @Test
  void notBefore는_UTC_timestamptz로_명시_bind한다() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.any(SqlParameterSource.class),
            eq(Boolean.class)))
        .thenReturn(true);
    Instant notBefore = Instant.parse("2026-09-14T01:02:03Z");

    assertThat(
            new JdbcRecentAuthSessionGateway(jdbc)
                .isRecent(UUID.randomUUID(), UUID.randomUUID(), notBefore))
        .isTrue();

    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).queryForObject(anyString(), parameters.capture(), eq(Boolean.class));
    assertThat(parameters.getValue().getValue("notBefore"))
        .isEqualTo(notBefore.atOffset(ZoneOffset.UTC));
  }
}
