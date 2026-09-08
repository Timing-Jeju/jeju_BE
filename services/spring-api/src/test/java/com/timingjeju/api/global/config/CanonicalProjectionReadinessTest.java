package com.timingjeju.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CanonicalProjectionReadinessTest {
  @Test
  void 구현된_계약만_투영하고_미구현_계약은_건너뛴다() {
    var policy =
        CanonicalProjectionReadiness.from(
            catalog(row("weather", "ready"), row("places", "not-ready")));
    assertThat(policy.shouldProject("weather")).isTrue();
    assertThat(policy.shouldProject("places")).isFalse();
  }

  @Test
  void 누락_중복_잘못된_readiness는_조용히_건너뛰지_않는다() {
    assertThatThrownBy(() -> CanonicalProjectionReadiness.from(Map.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalProjectionReadiness.from(
                    catalog(row("weather", "ready"), row("weather", "not-ready"))))
        .isInstanceOf(IllegalStateException.class);
    for (Object status : List.of("unknown", "READY", " ready", false, 1, List.of())) {
      assertThatThrownBy(() -> CanonicalProjectionReadiness.from(catalog(row("weather", status))))
          .isInstanceOf(IllegalStateException.class);
    }
    var missing = Map.<String, Object>of("domain", "weather", "readiness", Map.of());
    assertThatThrownBy(() -> CanonicalProjectionReadiness.from(catalog(missing)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalProjectionReadiness.from(catalog(row("weather", "ready")))
                    .shouldProject("absent"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void readiness_근거가_상태와_모순되거나_없으면_실패한다() {
    for (Object evidence : List.of(Map.of(), "path", true, List.of("path"))) {
      var implementation = Map.of("status", "ready", "evidence", evidence);
      assertThatThrownBy(
              () ->
                  CanonicalProjectionReadiness.from(
                      catalog(
                          Map.of(
                              "domain",
                              "weather",
                              "readiness",
                              Map.of("implementation", implementation)))))
          .isInstanceOf(IllegalStateException.class);
    }
    for (var implementation :
        List.of(
            Map.of("status", "ready"),
            Map.of("status", "not-ready", "evidence", Map.of("controller", "path")))) {
      assertThatThrownBy(
              () ->
                  CanonicalProjectionReadiness.from(
                      catalog(
                          Map.of(
                              "domain",
                              "weather",
                              "readiness",
                              Map.of("implementation", implementation)))))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @SafeVarargs
  private static Map<String, Object> catalog(Map<String, Object>... rows) {
    return Map.of("domainContracts", List.of(rows));
  }

  private static Map<String, Object> row(String domain, Object status) {
    var implementation = new LinkedHashMap<String, Object>();
    implementation.put("status", status);
    implementation.put(
        "evidence", "ready".equals(status) ? Map.of("controller", "verified.java") : null);
    return Map.of("domain", domain, "readiness", Map.of("implementation", implementation));
  }
}
