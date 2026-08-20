package com.timingjeju.api.global.staypolicy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.timingjeju.api.application.staypolicy.StayPolicyResolutionException;
import com.timingjeju.api.application.staypolicy.StayPolicySubject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class JdbcStayPolicyStoreTest {

  @Test
  void batch_DB_read_failure만_message와_cause없는_typed_failure로_변환한다() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    doThrow(new DataAccessResourceFailureException("select credential from secret_table"))
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    JdbcStayPolicyStore store = new JdbcStayPolicyStore(jdbc);

    assertThatThrownBy(
            () ->
                store.findActive(
                    List.of(
                        new StayPolicySubject(
                            UUID.fromString("32000000-0000-0000-0000-000000000001"), "VE"))))
        .isExactlyInstanceOf(StayPolicyResolutionException.class)
        .hasNoCause()
        .hasMessage(null);
  }
}
