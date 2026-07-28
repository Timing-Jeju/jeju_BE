package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SecurityConfigurationValidationTest {

  @Test
  void 기본_운영_환경의_HTTP_issuer는_시작_설정이_실패한다() {
    SupabaseJwtProperties properties =
        jwksProperties(
            "http://project.supabase.co/auth/v1",
            "https://project.supabase.co/auth/v1/.well-known/jwks.json");

    assertThatThrownBy(
            () -> new SupabaseJwtDecoderFactory(properties, productionJwksPolicy()).create())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTPS");
  }

  @Test
  void 기본_운영_환경의_HTTP_JWKS는_시작_설정이_실패한다() {
    SupabaseJwtProperties properties =
        jwksProperties(
            "https://project.supabase.co/auth/v1",
            "http://project.supabase.co/auth/v1/.well-known/jwks.json");

    assertThatThrownBy(
            () -> new SupabaseJwtDecoderFactory(properties, productionJwksPolicy()).create())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTPS");
  }

  @Test
  void 기본_운영_환경의_HTTPS_issuer와_JWKS는_허용한다() {
    SupabaseJwtProperties properties =
        jwksProperties(
            "https://project.supabase.co/auth/v1",
            "https://project.supabase.co/auth/v1/.well-known/jwks.json");

    assertThatCode(() -> new SupabaseJwtDecoderFactory(properties, productionJwksPolicy()).create())
        .doesNotThrowAnyException();
  }

  @Test
  void 로컬_환경은_loopback_HTTP_JWKS만_허용한다() {
    SupabaseJwtProperties loopback =
        jwksProperties(
            "http://127.0.0.1:54321/auth/v1",
            "http://127.0.0.1:54321/auth/v1/.well-known/jwks.json");
    SupabaseJwtProperties privateNetwork =
        jwksProperties(
            "http://192.168.10.10:54321/auth/v1",
            "http://192.168.10.10:54321/auth/v1/.well-known/jwks.json");

    assertThatCode(() -> new SupabaseJwtDecoderFactory(loopback, localJwksPolicy()).create())
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> new SupabaseJwtDecoderFactory(privateNetwork, localJwksPolicy()).create())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("로컬");
  }

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

    assertThatThrownBy(
            () -> new SupabaseJwtDecoderFactory(properties, productionJwksPolicy()).create())
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

    assertThatThrownBy(
            () ->
                new SupabaseJwtDecoderFactory(
                        properties,
                        new SecurityRuntimePolicy(
                            SecurityRuntimeEnvironment.PRODUCTION, JwtDecoderMode.HS256))
                    .create())
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

    assertThatThrownBy(
            () -> new SupabaseJwtDecoderFactory(properties, productionJwksPolicy()).create())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SUPABASE_JWT_SECRET");
  }

  private SupabaseJwtProperties jwksProperties(String issuer, String jwksUrl) {
    return new SupabaseJwtProperties(
        JwtDecoderMode.JWKS,
        URI.create(issuer),
        "authenticated",
        URI.create(jwksUrl),
        "",
        Duration.ofSeconds(30));
  }

  private SecurityRuntimePolicy productionJwksPolicy() {
    return new SecurityRuntimePolicy(SecurityRuntimeEnvironment.PRODUCTION, JwtDecoderMode.JWKS);
  }

  private SecurityRuntimePolicy localJwksPolicy() {
    return new SecurityRuntimePolicy(SecurityRuntimeEnvironment.LOCAL, JwtDecoderMode.JWKS);
  }
}
