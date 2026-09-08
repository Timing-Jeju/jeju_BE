package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

@Tag("unit")
class ProfileImageEnvironmentBindingTest {

  @Test
  void 표준_Supabase_환경변수를_ProfileImageProperties에_바인딩한다() throws Exception {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test-standard-env",
                Map.of(
                    "SUPABASE_URL", "https://profile-binding.example.invalid",
                    "SUPABASE_SERVICE_ROLE_KEY", "test-only-server-secret",
                    "PROFILE_IMAGE_CONNECT_TIMEOUT", "3s",
                    "PROFILE_IMAGE_READ_TIMEOUT", "7s",
                    "PROFILE_IMAGE_MAINTENANCE_ENABLED", "true",
                    "PROFILE_IMAGE_CLEANUP_FIXED_DELAY", "PT2M",
                    "PROFILE_IMAGE_ORPHAN_FIXED_DELAY", "PT12H",
                    "PROFILE_IMAGE_MAINTENANCE_INITIAL_DELAY", "PT30S")));
    new YamlPropertySourceLoader()
        .load(
            "main-application.yml",
            new FileSystemResource(Path.of("src", "main", "resources", "application.yml")))
        .forEach(environment.getPropertySources()::addLast);

    assertThat(environment.getProperty("app.profile-image.supabase-url"))
        .isEqualTo("https://profile-binding.example.invalid");
    ProfileImageProperties properties =
        Binder.get(environment)
            .bind("app.profile-image", ProfileImageProperties.class)
            .orElseThrow(() -> new AssertionError("app.profile-image must be bound"));

    assertThat(properties.supabaseUrl())
        .isEqualTo(URI.create("https://profile-binding.example.invalid"));
    assertThat(properties.serviceRoleKey()).isEqualTo("test-only-server-secret");
    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(7));

    ProfileImageMaintenanceProperties maintenance =
        Binder.get(environment)
            .bind("app.profile-image.maintenance", ProfileImageMaintenanceProperties.class)
            .orElseThrow(() -> new AssertionError("profile image maintenance must be bound"));
    assertThat(maintenance.enabled()).isTrue();
    assertThat(maintenance.cleanupFixedDelay()).isEqualTo(Duration.ofMinutes(2));
    assertThat(maintenance.orphanFixedDelay()).isEqualTo(Duration.ofHours(12));
    assertThat(maintenance.initialDelay()).isEqualTo(Duration.ofSeconds(30));
  }
}
