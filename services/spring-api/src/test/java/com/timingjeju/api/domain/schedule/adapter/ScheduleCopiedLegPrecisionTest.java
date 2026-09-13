package com.timingjeju.api.domain.schedule.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class ScheduleCopiedLegPrecisionTest {
  @Test
  void 복사할_구간의_null_분을_JDBC_기본값_영으로_바꾸지_않는다() throws Exception {
    var row = mock(ResultSet.class);
    var time = Timestamp.from(Instant.parse("2026-10-01T01:00:00Z"));
    when(row.getTimestamp("planned_departure_at")).thenReturn(time);
    when(row.getTimestamp("planned_arrival_at")).thenReturn(time);
    when(row.getObject("wait_minutes", Integer.class)).thenReturn(null);
    when(row.getObject("ride_minutes", Integer.class)).thenReturn(null);
    when(row.getString("facts")).thenReturn("{\"generation\":{\"precision\":{}}}");
    Object leg =
        ReflectionTestUtils.invokeMethod(JdbcScheduleMutationStore.class, "sourceLeg", row);
    assertThat((Object) ReflectionTestUtils.invokeMethod(leg, "waitMinutes")).isNull();
    assertThat((Object) ReflectionTestUtils.invokeMethod(leg, "rideMinutes")).isNull();
    assertThat((Object) ReflectionTestUtils.invokeMethod(leg, "facts"))
        .isEqualTo("{\"generation\":{\"precision\":{}}}");
    verify(row, never()).getInt("wait_minutes");
    verify(row, never()).getInt("ride_minutes");
  }
}
