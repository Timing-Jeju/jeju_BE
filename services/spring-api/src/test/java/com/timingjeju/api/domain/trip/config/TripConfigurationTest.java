package com.timingjeju.api.domain.trip.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.global.security.SecurityRuntimeEnvironment;
import com.timingjeju.api.global.security.SecurityRuntimeEnvironmentResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class TripConfigurationTest {
  private final TripConfiguration configuration = new TripConfiguration();

  @Test
  void trip_cursor는_trip_전용_key로_생성되고_places_codec_type과_충돌하지_않는다() {
    TripConfiguration.TripCursorCodec configured =
        configuration.tripCursorCodec("issue-44-trip-cursor-signing-key-at-least-32-bytes", false);

    assertThat(configured.value()).isNotNull();
    assertThat(configured)
        .isNotInstanceOf(com.timingjeju.api.application.pagination.CursorCodec.class);
  }

  @Test
  void non_local_runtime은_trip_cursor_key가_없으면_fail_closed한다() {
    for (String profile : new String[] {null, "staging", "test", "prod", "production"}) {
      MockEnvironment environment = new MockEnvironment();
      if (profile != null) {
        environment.setActiveProfiles(profile);
        environment.setProperty("spring.profiles.active", profile);
      }

      boolean localRuntime =
          SecurityRuntimeEnvironmentResolver.resolve(environment).environment()
              == SecurityRuntimeEnvironment.LOCAL;
      assertThatThrownBy(() -> configuration.tripCursorCodec("", localRuntime))
          .as(String.valueOf(profile))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("APP_TRIPS_CURSOR_SIGNING_KEY");
    }
  }

  @Test
  void exact_local_runtime만_trip_cursor_key를_안전하게_임시_생성한다() {
    for (String profile : new String[] {"local", "local-hs256"}) {
      MockEnvironment environment =
          new MockEnvironment().withProperty("spring.profiles.active", profile);

      boolean localRuntime =
          SecurityRuntimeEnvironmentResolver.resolve(environment).environment()
              == SecurityRuntimeEnvironment.LOCAL;
      assertThat(configuration.tripCursorCodec("", localRuntime).value()).as(profile).isNotNull();
    }
  }
}
