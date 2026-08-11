package com.timingjeju.api.global.externalapi;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

final class ExternalApiRuntimeEnvironmentResolver {

  private static final Set<String> LOCAL_PROFILES = Set.of("local", "local-hs256");

  private ExternalApiRuntimeEnvironmentResolver() {}

  static ExternalApiRuntimeEnvironment resolve(Environment environment) {
    String rawProfiles = environment.getProperty("spring.profiles.active");
    if (rawProfiles != null) {
      for (String profile : rawProfiles.split(",", -1)) {
        if (profile.isBlank() || !profile.equals(profile.trim())) {
          throw new IllegalStateException("외부 API runtime profile에는 빈 값이나 공백을 사용할 수 없습니다.");
        }
      }
    }

    List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
    for (String profile : activeProfiles) {
      String lowercase = profile.toLowerCase(Locale.ROOT);
      if (!profile.equals(lowercase) && LOCAL_PROFILES.contains(lowercase)) {
        throw new IllegalStateException("외부 API local profile 이름이 정확하지 않습니다: " + profile);
      }
    }
    if (activeProfiles.size() == 1 && LOCAL_PROFILES.contains(activeProfiles.getFirst())) {
      return ExternalApiRuntimeEnvironment.LOCAL;
    }
    if (activeProfiles.stream().anyMatch(LOCAL_PROFILES::contains)) {
      throw new IllegalStateException("외부 API local profile은 다른 profile과 함께 사용할 수 없습니다.");
    }
    return ExternalApiRuntimeEnvironment.PRODUCTION;
  }
}
