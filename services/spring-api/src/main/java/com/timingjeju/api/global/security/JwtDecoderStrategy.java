package com.timingjeju.api.global.security;

import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public interface JwtDecoderStrategy {

  JwtDecoderMode mode();

  NimbusJwtDecoder create(
      SupabaseJwtProperties properties, SecurityRuntimeEnvironment runtimeEnvironment);
}
