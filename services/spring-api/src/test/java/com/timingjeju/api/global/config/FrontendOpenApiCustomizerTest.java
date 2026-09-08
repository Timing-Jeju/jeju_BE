package com.timingjeju.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FrontendOpenApiCustomizerTest {

  @Test
  void implementation_ready만_canonical_projection을_활성화한다() {
    assertThat(
            FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                catalog(domain("weather-forecast", "ready")), "weather-forecast"))
        .isTrue();
    assertThat(
            FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                catalog(domain("weather-forecast", "not-ready")), "weather-forecast"))
        .isFalse();
  }

  @Test
  void domain_readiness_row가_없으면_명시적_구성오류로_실패한다() {
    assertThatThrownBy(
            () ->
                FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                    catalog(domain("places", "ready")), "weather-forecast"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("weather-forecast")
        .hasMessageContaining("readiness");
  }

  @Test
  void implementation_readiness_구조나_status가_비정상이면_실패한다() {
    Map<String, Object> missingImplementation = domain("weather-forecast", "ready");
    map(missingImplementation.get("readiness")).remove("implementation");

    Map<String, Object> missingStatus = domain("weather-forecast", "ready");
    map(map(missingStatus.get("readiness")).get("implementation")).remove("status");

    for (Map<String, Object> malformed :
        List.of(
            missingImplementation,
            missingStatus,
            domain("weather-forecast", "pending"),
            domain("weather-forecast", 1))) {
      assertThatThrownBy(
              () ->
                  FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                      catalog(malformed), "weather-forecast"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("weather-forecast")
          .hasMessageContaining("implementation readiness");
    }
  }

  private static Map<String, Object> catalog(Map<String, Object> domain) {
    return Map.of("domainContracts", List.of(domain));
  }

  private static Map<String, Object> domain(String name, Object status) {
    Map<String, Object> implementation = new LinkedHashMap<>();
    implementation.put("status", status);
    Map<String, Object> readiness = new LinkedHashMap<>();
    readiness.put("implementation", implementation);
    Map<String, Object> domain = new LinkedHashMap<>();
    domain.put("domain", name);
    domain.put("readiness", readiness);
    return domain;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
