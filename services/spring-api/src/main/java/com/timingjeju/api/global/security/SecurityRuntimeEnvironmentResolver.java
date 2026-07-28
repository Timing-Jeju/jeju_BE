package com.timingjeju.api.global.security;

import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;

public final class SecurityRuntimeEnvironmentResolver {

  private static final Set<String> LOCAL_PROFILES = Set.of("local", "local-hs256");
  private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

  private SecurityRuntimeEnvironmentResolver() {}

  public static SecurityRuntimeEnvironment resolve(Environment environment) {
    Set<String> activeProfiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
    boolean local = activeProfiles.stream().anyMatch(LOCAL_PROFILES::contains);
    boolean production = activeProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
    if (local && production) {
      throw new IllegalStateException("로컬과 운영 보안 profile을 동시에 활성화할 수 없습니다.");
    }
    return local ? SecurityRuntimeEnvironment.LOCAL : SecurityRuntimeEnvironment.PRODUCTION;
  }
}
