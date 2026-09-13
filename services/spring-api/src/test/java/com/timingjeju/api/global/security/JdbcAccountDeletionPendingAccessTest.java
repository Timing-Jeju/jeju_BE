package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("unit")
class JdbcAccountDeletionPendingAccessTest {
  @Test
  void queued_running만_공통_gate의_pending으로_조회한다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(
            contains("status in ('queued', 'running')"), anyMap(), eq(Boolean.class)))
        .thenReturn(true);

    boolean pending =
        new JdbcAccountDeletionPendingAccess(jdbc)
            .isPending(UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7f40"));

    assertThat(pending).isTrue();
  }
}
