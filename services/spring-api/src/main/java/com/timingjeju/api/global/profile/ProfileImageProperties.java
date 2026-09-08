package com.timingjeju.api.global.profile;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.profile-image")
public record ProfileImageProperties(
    URI supabaseUrl,
    String serviceRoleKey,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout) {

  @Override
  public String toString() {
    return "ProfileImageProperties[supabaseUrl="
        + (supabaseUrl == null ? "[EMPTY]" : "[CONFIGURED]")
        + ",serviceRoleKey=<redacted>,connectTimeout="
        + connectTimeout
        + ",readTimeout="
        + readTimeout
        + "]";
  }
}
