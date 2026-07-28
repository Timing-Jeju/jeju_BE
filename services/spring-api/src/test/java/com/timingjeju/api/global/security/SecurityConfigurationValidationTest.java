package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SecurityConfigurationValidationTest {

  @Test
  void 운영_JWKS_URL이_없으면_시작_설정이_실패한다() {
    SupabaseJwtProperties properties =
        new SupabaseJwtProperties(
            JwtDecoderMode.JWKS,
            URI.create("https://project.supabase.co/auth/v1"),
            "authenticated",
            null,
            "",
            Duration.ofSeconds(30));

    assertThatThrownBy(() -> new SupabaseJwtDecoderFactory(properties, false).create())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SUPABASE_JWKS_URL");
  }

  @Test
  void 운영에서_HS256_전략을_선택하면_실패한다() {
    SupabaseJwtProperties properties =
        new SupabaseJwtProperties(
            JwtDecoderMode.HS256,
            URI.create("https://project.supabase.co/auth/v1"),
            "authenticated",
            null,
            "test-only-secret-that-is-long-enough",
            Duration.ofSeconds(30));

    assertThatThrownBy(() -> new SupabaseJwtDecoderFactory(properties, false).create())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("로컬");
  }

  @Test
  void 운영에서_shared_secret이_주입되면_사용하지_않아도_실패한다() {
    SupabaseJwtProperties properties =
        new SupabaseJwtProperties(
            JwtDecoderMode.JWKS,
            URI.create("https://project.supabase.co/auth/v1"),
            "authenticated",
            URI.create("https://project.supabase.co/auth/v1/.well-known/jwks.json"),
            "test-only-secret-that-is-long-enough",
            Duration.ofSeconds(30));

    assertThatThrownBy(() -> new SupabaseJwtDecoderFactory(properties, false).create())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SUPABASE_JWT_SECRET");
  }
}
