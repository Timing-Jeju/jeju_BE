package com.timingjeju.api.global.security;

public record SecurityRuntimePolicy(
    SecurityRuntimeEnvironment environment, JwtDecoderMode allowedDecoderMode) {

  public SecurityRuntimePolicy {
    if (environment == null || allowedDecoderMode == null) {
      throw new IllegalArgumentException("보안 실행 환경과 JWT decoder mode는 필수입니다.");
    }
  }
}
