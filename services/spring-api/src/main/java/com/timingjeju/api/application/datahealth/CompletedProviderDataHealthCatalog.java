package com.timingjeju.api.application.datahealth;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CompletedProviderDataHealthCatalog {
  private static final Duration TOUR_API_TTL = Duration.ofHours(24);
  private static final List<ProviderDataHealthPolicy> POLICIES =
      validate(
          List.of(
              policy(
                  "TAGO",
                  "ArvlInfoInqireService",
                  "getSttnAcctoArvlPrearngeInfoList",
                  Duration.ofSeconds(30)),
              policy("kma", "VilageFcstInfoService_2.0", "getUltraSrtFcst", Duration.ofHours(1)),
              policy("kma", "VilageFcstInfoService_2.0", "getUltraSrtNcst", Duration.ofMinutes(10)),
              policy("kma", "VilageFcstInfoService_2.0", "getVilageFcst", Duration.ofHours(3)),
              policy("tour-api", "KorService2", "areaBasedSyncList2", TOUR_API_TTL),
              policy("tour-api", "KorService2", "locationBasedList2", TOUR_API_TTL),
              policy("tour-api", "KorService2", "searchKeyword2", TOUR_API_TTL),
              policy("tour-api", "KorService2", "searchStay2", TOUR_API_TTL)));
  private static final List<ProviderDataHealthKey> KEYS =
      POLICIES.stream().map(ProviderDataHealthPolicy::key).toList();

  private CompletedProviderDataHealthCatalog() {}

  public static List<ProviderDataHealthPolicy> policies() {
    return POLICIES;
  }

  public static List<ProviderDataHealthPolicy> policies(
      boolean tourApiEnabled, boolean tagoEnabled, boolean kmaEnabled) {
    return POLICIES.stream()
        .map(
            policy ->
                new ProviderDataHealthPolicy(
                    policy.key(),
                    policy.ttl(),
                    switch (policy.key().provider()) {
                      case "tour-api" -> tourApiEnabled;
                      case "TAGO" -> tagoEnabled;
                      case "kma" -> kmaEnabled;
                      default -> throw new IllegalStateException("알 수 없는 완료 공급자입니다.");
                    }))
        .toList();
  }

  public static List<ProviderDataHealthKey> keys() {
    return KEYS;
  }

  public static CompletedProviderDataHealthSettings settings(
      boolean tourApiEnabled, boolean tagoEnabled, boolean kmaEnabled) {
    return new CompletedProviderDataHealthSettings(
        policies(tourApiEnabled, tagoEnabled, kmaEnabled));
  }

  private static ProviderDataHealthPolicy policy(
      String provider, String service, String operation, Duration ttl) {
    return new ProviderDataHealthPolicy(
        new ProviderDataHealthKey(provider, service, operation), ttl, true);
  }

  private static List<ProviderDataHealthPolicy> validate(List<ProviderDataHealthPolicy> policies) {
    List<ProviderDataHealthPolicy> ordered =
        policies.stream().sorted((left, right) -> left.key().compareTo(right.key())).toList();
    Set<ProviderDataHealthKey> unique = new HashSet<>();
    if (ordered.size() != 8 || ordered.stream().anyMatch(policy -> !unique.add(policy.key()))) {
      throw new IllegalStateException("완료 공급자 catalog가 올바르지 않습니다.");
    }
    return ordered;
  }
}
