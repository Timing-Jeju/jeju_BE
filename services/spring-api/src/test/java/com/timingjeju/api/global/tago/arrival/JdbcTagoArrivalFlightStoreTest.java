package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightDecision;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStatus;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

  @Test
  void current_fence는_owner_generation_RUNNING_DB_lease를_모두_검증한다() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(1);
    JdbcTagoArrivalFlightStore store = new JdbcTagoArrivalFlightStore(jdbc);

    store.lockCurrent(new TagoArrivalFlightLease(FINGERPRINT, 7, OWNER));

    assertThat(jdbc.sql).contains("for update").contains("lease_expires_at > clock_timestamp()");
    assertThat(jdbc.args).containsExactly(FINGERPRINT, 7L, OWNER);
  }

  @Test
  void cleanup은_batch32_current_fingerprint를_제외하고_SKIP_LOCKED한다() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(32);
    JdbcTagoArrivalFlightStore store = new JdbcTagoArrivalFlightStore(jdbc);

    assertThat(store.cleanupExpiredTerminals(FINGERPRINT, 32)).isEqualTo(32);

    assertThat(jdbc.sql)
        .contains("state <> 'running'")
        .contains("fingerprint <> ?")
        .contains("for update skip locked")
        .contains("limit ?");
    assertThat(jdbc.args).containsExactly(FINGERPRINT, 32);
  }

  @ParameterizedTest
  @CsvSource({
    "2026-08-22T00:00:25.657613854Z, 2026-08-22T00:00:25.657613Z",
    "2026-08-22T00:00:25.657613Z, 2026-08-22T00:00:25.657613Z"
  })
  void success_retain은_source_expires를_DB_microsecond로_floor_bind한다(
      String sourceExpires, String expectedBound) {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(1);
    JdbcTagoArrivalFlightStore store = new JdbcTagoArrivalFlightStore(jdbc);
    TagoArrivalFlightLease lease = new TagoArrivalFlightLease(FINGERPRINT, 7, OWNER);
    Instant sourceExpiresAt = Instant.parse(sourceExpires);
    Instant expected = Instant.parse(expectedBound);

    assertThat(store.completeSuccess(lease, sourceExpiresAt, Duration.ofSeconds(25))).isTrue();

    assertThat(jdbc.sql)
        .contains("least(?::timestamptz")
        .contains("?::timestamptz > clock_timestamp()");
    assertThat(jdbc.args).contains(java.sql.Timestamp.from(expected));
  }

  @Test
  void observe는_existing_row를_쓰지않고_insert_reclaim_skip_locked_MVCC_read로_분리한다() throws Exception {
    ObserveJdbcTemplate jdbc = new ObserveJdbcTemplate();
    JdbcTagoArrivalFlightStore store = new JdbcTagoArrivalFlightStore(jdbc);

    assertThat(
            store.observeOrClaim(
                FINGERPRINT, OWNER, Duration.ofSeconds(12), Duration.ofSeconds(12)))
        .extracting(TagoArrivalFlightDecision::status)
        .isEqualTo(TagoArrivalFlightStatus.RUNNING);

    assertThat(jdbc.queries).hasSize(3);
    assertThat(jdbc.queries.get(0))
        .contains("on conflict (fingerprint) do nothing")
        .doesNotContain("do update set");
    assertThat(jdbc.queries.get(1))
        .contains("for update skip locked")
        .contains("update public.tago_arrival_flights")
        .contains("returning");
    assertThat(jdbc.queries.get(2))
        .contains("select state, outcome_code, owner_token, generation")
        .contains("state='running'")
        .contains("retain_until > clock_timestamp()")
        .doesNotContain("for update");
  }

  @Test
  void reclaim_contention이나_cleanup_race로_observe가_0행이면_CONTENDED다() {
    ObserveJdbcTemplate jdbc = new ObserveJdbcTemplate(false);
    JdbcTagoArrivalFlightStore store = new JdbcTagoArrivalFlightStore(jdbc);

    assertThat(
            store.observeOrClaim(
                FINGERPRINT, OWNER, Duration.ofSeconds(12), Duration.ofSeconds(12)))
        .extracting(TagoArrivalFlightDecision::status)
        .isEqualTo(TagoArrivalFlightStatus.CONTENDED);
    assertThat(jdbc.queries).hasSize(3);
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

    @Override
    public int update(String sql, Object... args) {
      return 0;
    }
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final int result;
    private String sql;
    private Object[] args;

    private RecordingJdbcTemplate(int result) {
      this.result = result;
    }

    @Override
    public int update(String sql, Object... args) {
      this.sql = sql;
      this.args = args;
      return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      this.sql = sql;
      this.args = args;
      return result == 1 ? List.of((T) Integer.valueOf(1)) : List.of();
    }
  }

  private static final class ObserveJdbcTemplate extends JdbcTemplate {
    private final List<String> queries = new java.util.ArrayList<>();
    private final boolean currentRow;

    private ObserveJdbcTemplate() {
      this(true);
    }

    private ObserveJdbcTemplate(boolean currentRow) {
      this.currentRow = currentRow;
    }

    @Override
    public int update(String sql, Object... args) {
      return 0;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      queries.add(sql);
      if (queries.size() < 3 || !currentRow) return List.of();
      try {
        return List.of(
            rowMapper.mapRow(
                row("running", null, UUID.fromString("39000000-0000-0000-0000-000000000002"), 3),
                0));
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }
  }
}
