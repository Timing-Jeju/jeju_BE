package com.timingjeju.api.global.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.cors")
public record AppCorsProperties(List<String> allowedOrigins) {

  public AppCorsProperties {
    allowedOrigins =
        allowedOrigins == null
            ? List.of()
            : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .distinct()
                .toList();
    if (allowedOrigins.stream().anyMatch(origin -> origin.equals("*"))) {
      throw new IllegalArgumentException("CORS 허용 Origin에는 wildcard를 사용할 수 없습니다.");
    }
  }
}
