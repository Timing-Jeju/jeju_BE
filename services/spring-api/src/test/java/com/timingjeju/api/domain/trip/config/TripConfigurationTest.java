package com.timingjeju.api.domain.trip.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

@Tag("unit")
class TripConfigurationTest {
  private final TripConfiguration configuration = new TripConfiguration();

  @Test
  void trip_cursor는_trip_전용_key로_생성되고_places_codec_type과_충돌하지_않는다() {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});

    TripConfiguration.TripCursorCodec configured =
        configuration.tripCursorCodec(
            "issue-44-trip-cursor-signing-key-at-least-32-bytes", environment);

    assertThat(configured.value()).isNotNull();
    assertThat(configured)
        .isNotInstanceOf(com.timingjeju.api.application.pagination.CursorCodec.class);
  }

  @Test
  void production은_trip_cursor_key가_없으면_fail_closed한다() {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

    assertThatThrownBy(() -> configuration.tripCursorCodec("", environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APP_TRIPS_CURSOR_SIGNING_KEY");
  }
}
