package com.timingjeju.api.global.security;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

public final class SecurityRuntimeEnvironmentResolver {

  private static final String LOCAL_JWKS_PROFILE = "local";
  private static final String LOCAL_HS256_PROFILE = "local-hs256";
  private static final Set<String> RUNTIME_PROFILES =
      Set.of(LOCAL_JWKS_PROFILE, LOCAL_HS256_PROFILE, "prod", "production");

  private SecurityRuntimeEnvironmentResolver() {}

  public static SecurityRuntimePolicy resolve(Environment environment) {
    validateRawProfiles(environment.getProperty("spring.profiles.active"));
    List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
    activeProfiles.forEach(SecurityRuntimeEnvironmentResolver::validateProfileSpelling);

    List<String> runtimeProfiles =
        activeProfiles.stream().filter(RUNTIME_PROFILES::contains).toList();
    if (!runtimeProfiles.isEmpty() && activeProfiles.size() != 1) {
      throw new IllegalStateException("보안 runtime profile은 다른 profile과 동시에 활성화할 수 없습니다.");
    }
    if (runtimeProfiles.size() > 1) {
      throw new IllegalStateException("보안 runtime profile은 하나만 활성화할 수 있습니다.");
    }
    if (activeProfiles.equals(List.of(LOCAL_JWKS_PROFILE))) {
      return new SecurityRuntimePolicy(SecurityRuntimeEnvironment.LOCAL, JwtDecoderMode.JWKS);
    }
    if (activeProfiles.equals(List.of(LOCAL_HS256_PROFILE))) {
      return new SecurityRuntimePolicy(SecurityRuntimeEnvironment.LOCAL, JwtDecoderMode.HS256);
    }
    return new SecurityRuntimePolicy(SecurityRuntimeEnvironment.PRODUCTION, JwtDecoderMode.JWKS);
  }

  private static void validateRawProfiles(String rawProfiles) {
    if (rawProfiles == null) {
      return;
    }
    for (String profile : rawProfiles.split(",", -1)) {
      if (profile.isBlank() || !profile.equals(profile.trim())) {
        throw new IllegalStateException("Spring profile에는 빈 값이나 앞뒤 공백을 사용할 수 없습니다.");
      }
    }
  }

  private static void validateProfileSpelling(String profile) {
    String lowercase = profile.toLowerCase(Locale.ROOT);
    if (!profile.equals(profile.trim())
        || (!RUNTIME_PROFILES.contains(profile)
            && (lowercase.contains("local")
                || RUNTIME_PROFILES.stream().anyMatch(value -> value.equalsIgnoreCase(profile))))) {
      throw new IllegalStateException("보안 runtime profile 이름이 정확하지 않습니다: " + profile);
    }
  }
}
