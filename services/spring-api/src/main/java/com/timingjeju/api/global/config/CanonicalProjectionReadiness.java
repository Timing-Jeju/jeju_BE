package com.timingjeju.api.global.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implementation readiness is independent of metadata/example publication readiness. */
final class CanonicalProjectionReadiness {
  private final Map<String, Boolean> domains;

  private CanonicalProjectionReadiness(Map<String, Boolean> domains) {
    this.domains = Map.copyOf(domains);
  }

  static CanonicalProjectionReadiness from(Map<String, Object> catalog) {
    if (!(catalog.get("domainContracts") instanceof List<?> rows) || rows.isEmpty()) {
      throw invalid();
    }
    var domains = new LinkedHashMap<String, Boolean>();
    for (Object value : rows) {
      if (!(value instanceof Map<?, ?> row)
          || !(row.get("domain") instanceof String domain)
          || domain.isBlank()
          || !domain.equals(domain.trim())
          || !(row.get("readiness") instanceof Map<?, ?> readiness)
          || !(readiness.get("implementation") instanceof Map<?, ?> implementation)
          || !(implementation.get("status") instanceof String status)
          || !(status.equals("ready") || status.equals("not-ready"))
          || !implementation.containsKey("evidence")) {
        throw invalid();
      }
      Object evidence = implementation.get("evidence");
      boolean ready = status.equals("ready");
      if ((ready && (!(evidence instanceof Map<?, ?> map) || map.isEmpty()))
          || (!ready && evidence != null)
          || domains.putIfAbsent(domain, ready) != null) {
        throw invalid();
      }
    }
    return new CanonicalProjectionReadiness(domains);
  }

  boolean shouldProject(String domain) {
    Boolean ready = domains.get(domain);
    if (ready == null) throw invalid();
    return ready;
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("OpenAPI domain implementation readiness 구성이 올바르지 않습니다.");
  }
}
