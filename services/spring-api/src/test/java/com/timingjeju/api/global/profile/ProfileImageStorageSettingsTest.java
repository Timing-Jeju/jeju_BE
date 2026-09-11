package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageStorageSettingsTest {

  @Test
  void missing_URL과_key는_provider_GET을_위해_disabled로_허용한다() {
    ProfileImageStorageSettings settings =
        ProfileImageStorageSettings.from(
            new ProfileImageProperties(null, null, Duration.ofSeconds(2), Duration.ofSeconds(5)),
            false);

    assertThat(settings.enabled()).isFalse();
  }

  @Test
  void production은_HTTPS_only이고_URL_authority와_timeout을_fail_closed한다() {
    for (ProfileImageProperties invalid :
        new ProfileImageProperties[] {
          properties("http://storage.example.invalid", Duration.ofSeconds(2)),
          properties("https://user@storage.example.invalid", Duration.ofSeconds(2)),
          properties("https://storage.example.invalid?leak=value", Duration.ofSeconds(2)),
          properties("https://storage.example.invalid#fragment", Duration.ofSeconds(2)),
          properties("https://storage.example.invalid", Duration.ZERO),
          properties("https://storage.example.invalid", Duration.ofSeconds(31))
        }) {
      assertThatThrownBy(() -> ProfileImageStorageSettings.from(invalid, false))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("test-service-role-secret");
    }
  }

  @Test
  void URL만_있는_기존_runtime은_disabled이고_key만_있으면_startup을_거부한다() {
    assertThat(
            ProfileImageStorageSettings.from(
                    new ProfileImageProperties(
                        URI.create("https://storage.example.invalid"),
                        null,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5)),
                    false)
                .enabled())
        .isFalse();
    assertThatThrownBy(
            () ->
                ProfileImageStorageSettings.from(
                    new ProfileImageProperties(
                        null,
                        "test-service-role-secret",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5)),
                    false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("test-service-role-secret");
  }

  @Test
  void local만_HTTP를_허용하고_toString은_service_role_key를_노출하지_않는다() {
    ProfileImageStorageSettings settings =
        ProfileImageStorageSettings.from(
            properties("http://127.0.0.1:54321", Duration.ofSeconds(2)), true);

    assertThat(settings.enabled()).isTrue();
    assertThat(settings.toString())
        .contains("serviceRoleKey=<redacted>")
        .doesNotContain("test-service-role-secret");
  }

  private static ProfileImageProperties properties(String url, Duration readTimeout) {
    return new ProfileImageProperties(
        URI.create(url), "test-service-role-secret", Duration.ofSeconds(2), readTimeout);
  }
}
