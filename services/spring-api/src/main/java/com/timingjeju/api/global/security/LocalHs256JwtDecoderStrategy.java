package com.timingjeju.api.global.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class LocalHs256JwtDecoderStrategy implements JwtDecoderStrategy {

  @Override
  public JwtDecoderMode mode() {
    return JwtDecoderMode.HS256;
  }

  @Override
  public NimbusJwtDecoder create(
      SupabaseJwtProperties properties, boolean localCompatibilityProfile) {
    if (!localCompatibilityProfile) {
      throw new IllegalStateException("HS256 shared secret 검증은 로컬 profile에서만 허용됩니다.");
    }
    if (properties.secret().isBlank()) {
      throw new IllegalStateException("로컬 HS256 검증에는 SUPABASE_JWT_SECRET이 필요합니다.");
    }
    byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < 32) {
      throw new IllegalStateException("SUPABASE_JWT_SECRET은 32바이트 이상이어야 합니다.");
    }
    SecretKey secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
  }
}
