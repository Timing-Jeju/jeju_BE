package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("unit")
class SecurityStartupValidationTest {

  private final ApplicationContextRunner jwtContextRunner =
      new ApplicationContextRunner().withUserConfiguration(JwtTestConfiguration.class);
  private final ApplicationContextRunner corsContextRunner =
      new ApplicationContextRunner().withUserConfiguration(CorsTestConfiguration.class);

  @Test
  void 기본과_production_context는_HTTP_issuer나_JWKS에서_실패한다() {
    jwtContextRunner
        .withPropertyValues(
            "app.security.jwt.issuer=http://project.supabase.co/auth/v1",
            "app.security.jwt.jwks-url=https://project.supabase.co/auth/v1/.well-known/jwks.json")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("SUPABASE_JWT_ISSUER는 기본/운영 환경에서 HTTPS URL이어야 합니다.");
            });
    jwtContextRunner
        .withPropertyValues(
            "spring.profiles.active=production",
            "app.security.jwt.issuer=https://project.supabase.co/auth/v1",
            "app.security.jwt.jwks-url=http://project.supabase.co/auth/v1/.well-known/jwks.json")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("SUPABASE_JWKS_URL는 기본/운영 환경에서 HTTPS URL이어야 합니다.");
            });
  }

  @Test
  void HTTPS와_local_loopback_context는_시작한다() {
    jwtContextRunner
        .withPropertyValues(
            "app.security.jwt.issuer=https://project.supabase.co/auth/v1",
            "app.security.jwt.jwks-url=https://project.supabase.co/auth/v1/.well-known/jwks.json")
        .run(context -> assertThat(context).hasNotFailed());
    jwtContextRunner
        .withPropertyValues(
            "spring.profiles.active=local",
            "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
            "app.security.jwt.jwks-url=http://127.0.0.1:54321/auth/v1/.well-known/jwks.json")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void 정확한_local은_JWKS만_local_hs256은_HS256만_허용한다() {
    jwtContextRunner
        .withPropertyValues(
            "spring.profiles.active=local",
            "app.security.jwt.mode=jwks",
            "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
            "app.security.jwt.jwks-url=http://127.0.0.1:54321/auth/v1/.well-known/jwks.json")
        .run(context -> assertThat(context).hasNotFailed());
    jwtContextRunner
        .withPropertyValues(
            "spring.profiles.active=local-hs256",
            "app.security.jwt.mode=hs256",
            "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
            "app.security.jwt.secret=test-" + "only-secret-that-is-long-enough")
        .run(context -> assertThat(context).hasNotFailed());
    jwtContextRunner
        .withPropertyValues(
            "spring.profiles.active=local",
            "app.security.jwt.mode=hs256",
            "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
            "app.security.jwt.secret=test-" + "only-secret-that-is-long-enough")
        .run(context -> assertThat(context).hasFailed());
    jwtContextRunner
        .withPropertyValues(
            "spring.profiles.active=local-hs256",
            "app.security.jwt.mode=jwks",
            "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
            "app.security.jwt.jwks-url=http://127.0.0.1:54321/auth/v1/.well-known/jwks.json")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void local_보안_profile_혼합과_유사_profile은_context를_실패시킨다() {
    for (String profiles :
        new String[] {"local,staging", "local,test", "local-hs256,staging", "local,production"}) {
      jwtContextRunner
          .withPropertyValues(
              "spring.profiles.active=" + profiles,
              "app.security.jwt.mode=jwks",
              "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
              "app.security.jwt.jwks-url=http://127.0.0.1:54321/auth/v1/.well-known/jwks.json")
          .run(context -> assertThat(context).as(profiles).hasFailed());
    }
    for (String profile : new String[] {"local-preview", "LOCAL", "Local", "local-HS256"}) {
      jwtContextRunner
          .withPropertyValues(
              "spring.profiles.active=" + profile,
              "app.security.jwt.mode=jwks",
              "app.security.jwt.issuer=https://project.supabase.co/auth/v1",
              "app.security.jwt.jwks-url=https://project.supabase.co/auth/v1/.well-known/jwks.json")
          .run(context -> assertThat(context).as(profile).hasFailed());
    }
  }

  @Test
  void CORS_allowlist가_없거나_정규화_후_비면_context가_실패한다() {
    corsContextRunner.run(context -> assertThat(context).hasFailed());
    corsContextRunner
        .withPropertyValues("app.security.cors.allowed-origins= , ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void 정확한_CORS_allowlist는_context에서_trim과_중복제거된다() {
    corsContextRunner
        .withPropertyValues(
            "app.security.cors.allowed-origins= http://localhost:3000 , https://app.timing-jeju.test , http://localhost:3000 , HTTP://[::1]:80 , http://127.0.0.1:80 ")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AppCorsProperties.class).allowedOrigins())
                  .containsExactly(
                      "http://localhost:3000",
                      "https://app.timing-jeju.test",
                      "http://[::1]",
                      "http://127.0.0.1");
            });
  }

  @Test
  void 문법에_맞지_않는_CORS_origin은_context가_실패한다() {
    for (String origin :
        List.of(
            "ftp://example.com",
            "https://user@example.com",
            "https://example.com/path",
            "https://example.com?query=1",
            "https://*.example.com",
            "relative/path",
            "http://example.com:65536",
            "HTTP://[0:0:0:0:0:0:0:1]:80",
            "http://0177.0.0.1:80")) {
      corsContextRunner
          .withPropertyValues("app.security.cors.allowed-origins=" + origin)
          .run(context -> assertThat(context).as(origin).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SupabaseJwtProperties.class)
  static class JwtTestConfiguration {

    @Bean
    JwtDecoder jwtDecoder(SupabaseJwtProperties properties, Environment environment) {
      return new SupabaseJwtDecoderFactory(
              properties,
              SecurityRuntimeEnvironmentResolver.resolve(environment),
              List.of(new JwksJwtDecoderStrategy(), new LocalHs256JwtDecoderStrategy()))
          .create();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AppCorsProperties.class)
  static class CorsTestConfiguration {}
}
