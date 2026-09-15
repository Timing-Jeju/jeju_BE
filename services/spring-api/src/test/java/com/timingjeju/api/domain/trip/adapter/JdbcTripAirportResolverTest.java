package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("unit")
class JdbcTripAirportResolverTest {
  private static final UUID AIRPORT = UUID.fromString("53000000-0000-0000-0000-000000000099");

  @ParameterizedTest
  @ValueSource(strings = {"", "unknown", "1-1-1-1-1", "53000000-0000-0000-0000-0000000000AA"})
  void 미설정과_비정규_ID는_DB_조회나_외부_호출_없이_미확정이다(String configured) {
    var jdbc = mock(JdbcTemplate.class);
    assertThat(new JdbcTripAirportResolver(jdbc, configured).findApproved()).isEmpty();
    verifyNoInteractions(jdbc);
  }

  @Test
  void 승인_조회에_없는_ID는_설정값만으로_공항으로_승격하지_않는다() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(AIRPORT))).thenReturn(List.of());
    assertThat(new JdbcTripAirportResolver(jdbc, AIRPORT.toString()).findApproved()).isEmpty();
  }

  @Test
  void 승인된_장소만_잠금_조회하고_동일_canonical_ID를_반환한다() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(AIRPORT))).thenReturn(List.of(AIRPORT));
    assertThat(new JdbcTripAirportResolver(jdbc, AIRPORT.toString()).findApproved())
        .contains(AIRPORT);
    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(UUID.class), eq(AIRPORT));
    assertThat(sql.getValue())
        .contains(
            "name='제주국제공항'",
            "r.source_kind='tour_api'",
            "r.status='succeeded'",
            "content_id is not null",
            "not stale",
            "tombstoned_at is null",
            "source_deleted_at is null",
            "for share");
  }
}
