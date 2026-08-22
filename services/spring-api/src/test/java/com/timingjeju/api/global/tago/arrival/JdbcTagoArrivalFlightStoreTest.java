package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightDecision;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStatus;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@Tag("unit")
class JdbcTagoArrivalFlightStoreTest {
  private static final UUID OWNER = UUID.fromString("39000000-0000-0000-0000-000000000001");
  private static final String FINGERPRINT = "a".repeat(64);

  @Test
  void proposed_owner의_running은_LEADER이고_다른_owner는_RUNNING이다() throws Exception {
    ResultSet resultSet = row("running", null, OWNER, 3);

    assertThat(JdbcTagoArrivalFlightStore.mapDecision(resultSet, FINGERPRINT, OWNER).status())
        .isEqualTo(TagoArrivalFlightStatus.LEADER);
    assertThat(
            JdbcTagoArrivalFlightStore.mapDecision(
                    row(
                        "running",
                        null,
                        UUID.fromString("39000000-0000-0000-0000-000000000002"),
                        3),
                    FINGERPRINT,
                    OWNER)
                .status())
        .isEqualTo(TagoArrivalFlightStatus.RUNNING);
  }

  @Test
  void failed_row는_exact_domain_outcome만_복원한다() throws Exception {
    TagoArrivalFlightDecision decision =
        JdbcTagoArrivalFlightStore.mapDecision(
            row("failed", "empty_result", OWNER, 4), FINGERPRINT, OWNER);

    assertThat(decision.status()).isEqualTo(TagoArrivalFlightStatus.FAILED);
    assertThat(decision.outcome()).contains(TagoArrivalException.Code.EMPTY_RESULT);
  }

  @Test
  void invalid_row_mapping과_DB_failure는_raw_cause없는_DATA_UNAVAILABLE다() throws Exception {
    assertDataUnavailable(
        () ->
            JdbcTagoArrivalFlightStore.mapDecision(
                row("failed", "select password", OWNER, 0), FINGERPRINT, OWNER));

    JdbcTagoArrivalFlightStore store =
        new JdbcTagoArrivalFlightStore(
            new FailingJdbcTemplate(
                new DataAccessResourceFailureException("select password from flight")));
    assertDataUnavailable(
        () ->
            store.observeOrClaim(
                FINGERPRINT, OWNER, Duration.ofSeconds(12), Duration.ofSeconds(12)));
  }

  @Test
  void arbitrary_programmer_bug는_DATA_UNAVAILABLE로_숨기지_않는다() {
    IllegalStateException programmerBug = new IllegalStateException("mapper programmer bug");
    JdbcTagoArrivalFlightStore store =
        new JdbcTagoArrivalFlightStore(new FailingJdbcTemplate(programmerBug));

    assertThatThrownBy(
            () ->
                store.observeOrClaim(
                    FINGERPRINT, OWNER, Duration.ofSeconds(12), Duration.ofSeconds(12)))
        .isSameAs(programmerBug);
  }

  private static ResultSet row(String state, String outcome, UUID owner, long generation)
      throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("state")).thenReturn(state);
    when(resultSet.getString("outcome_code")).thenReturn(outcome);
    when(resultSet.getObject("owner_token", UUID.class)).thenReturn(owner);
    when(resultSet.getLong("generation")).thenReturn(generation);
    return resultSet;
  }

  private static void assertDataUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
              assertThat(failure.getMessage()).isEqualTo("DATA_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
            });
  }

  private static final class FailingJdbcTemplate extends JdbcTemplate {
    private final RuntimeException failure;

    private FailingJdbcTemplate(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      throw failure;
    }
  }
}
