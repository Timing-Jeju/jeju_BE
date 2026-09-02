package com.timingjeju.api.global.datahealth;

import java.util.Objects;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

public final class OpsJwtDecoderHolder implements JwtDecoder {
  private final JwtDecoder delegate;

  OpsJwtDecoderHolder(JwtDecoder delegate) {
    this.delegate = Objects.requireNonNull(delegate, "decoder는 필수입니다.");
  }

  @Override
  public Jwt decode(String token) {
    return delegate.decode(token);
  }
}
