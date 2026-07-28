package com.timingjeju.api.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import com.timingjeju.api.domain.auth.service.NaverUserInfoGateway;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.social-login.provider-ids=google,kakao,custom:naver"
    })
@AutoConfigureMockMvc
@Import(SocialLoginIntegrationTest.TestGatewayConfiguration.class)
class SocialLoginIntegrationTest {

  private static final String TRACE_ID_PATTERN = "[0-9a-f]{32}";
  private static final String TEST_JWT_SIGNING_KEY =
      "test-only-hs256-signing-key-with-at-least-32-bytes";

  @Autowired private MockMvc mockMvc;
  @Autowired private FakeNaverUserInfoGateway gateway;

  @DynamicPropertySource
  static void registerJwtProperties(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> TEST_JWT_SIGNING_KEY);
  }

  @BeforeEach
  void resetGateway() {
    gateway.reset();
  }

  @Test
  void 공급자_카탈로그는_공개_필드만_안정된_순서로_반환한다() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/social/providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providers.length()").value(3))
        .andExpect(jsonPath("$.providers[0].id").value("google"))
        .andExpect(jsonPath("$.providers[0].displayName").value("Google"))
        .andExpect(jsonPath("$.providers[1].id").value("kakao"))
        .andExpect(jsonPath("$.providers[2].id").value("custom:naver"))
        .andExpect(jsonPath("$.providers[2].displayName").value("Naver"));
  }

  @Test
  void 소셜_공개_경계는_정확한_두_GET_endpoint만_허용한다() throws Exception {
    mockMvc.perform(get("/api/v1/auth/social/unknown")).andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/v1/auth/social/providers")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(post("/api/v1/auth/social/naver/userinfo"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void opaque_Naver_Bearer는_Supabase_JWT로_해석하지_않고_표준_UserInfo로_반환한다() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/auth/social/naver/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sub").value("application-scoped-id"))
        .andExpect(jsonPath("$.email").value("user@example.test"))
        .andExpect(jsonPath("$.email_verified").doesNotExist())
        .andExpect(jsonPath("$.name").value("사용자"))
        .andExpect(jsonPath("$.preferred_username").value("별명"))
        .andExpect(jsonPath("$.picture").value("https://profile.example.test/user.png"));

    assertThat(gateway.callCount.get()).isEqualTo(1);
    assertThat(gateway.lastToken.get()).isEqualTo("opaque-provider-token");
  }

  @Test
  void 누락_중복_형식오류_query_form_Bearer는_upstream_호출없이_401이다() throws Exception {
    assertTokenInvalid(get("/api/v1/auth/social/naver/userinfo"));
    assertTokenInvalid(
        get("/api/v1/auth/social/naver/userinfo")
            .header(HttpHeaders.AUTHORIZATION, "Bearer first", "Bearer second"));
    assertTokenInvalid(
        get("/api/v1/auth/social/naver/userinfo")
            .header(HttpHeaders.AUTHORIZATION, "Basic not-allowed"));
    assertTokenInvalid(
        get("/api/v1/auth/social/naver/userinfo")
            .header(HttpHeaders.AUTHORIZATION, "Bearer first,second"));
    assertTokenInvalid(
        get("/api/v1/auth/social/naver/userinfo")
            .header(HttpHeaders.AUTHORIZATION, "Bearer first:second"));
    assertTokenInvalid(
        get("/api/v1/auth/social/naver/userinfo")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + "A".repeat(257)));
    assertTokenInvalid(
        get("/api/v1/auth/social/naver/userinfo?access_token=not-allowed")
            .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token"));

    assertThat(gateway.callCount.get()).isZero();
  }

  @Test
  void upstream_오류는_원본_응답_없이_분류된_JSON으로_반환한다() throws Exception {
    gateway.failure.set(new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_RATE_LIMITED));

    mockMvc
        .perform(
            get("/api/v1/auth/social/naver/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SOCIAL_NAVER_UPSTREAM_RATE_LIMITED"))
        .andExpect(jsonPath("$.message").value("네이버 로그인 서비스를 일시적으로 사용할 수 없습니다."))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)))
        .andExpect(jsonPath("$..providerToken").doesNotExist())
        .andExpect(jsonPath("$..mobile").doesNotExist());
  }

  @Test
  void application_rate_limit과_bulkhead_초과는_각각_429와_503이다() throws Exception {
    gateway.failure.set(
        new NaverUserInfoException(NaverUserInfoFailureCode.APPLICATION_RATE_LIMITED));
    mockMvc
        .perform(
            get("/api/v1/auth/social/naver/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("SOCIAL_NAVER_RATE_LIMITED"))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));

    gateway.failure.set(
        new NaverUserInfoException(NaverUserInfoFailureCode.APPLICATION_OVERLOADED));
    mockMvc
        .perform(
            get("/api/v1/auth/social/naver/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SOCIAL_NAVER_OVERLOADED"))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
  }

  private void assertTokenInvalid(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SOCIAL_NAVER_TOKEN_INVALID"))
        .andExpect(jsonPath("$.message").value("네이버 인증 정보를 확인할 수 없습니다."))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestGatewayConfiguration {

    @Bean
    @Primary
    FakeNaverUserInfoGateway fakeNaverUserInfoGateway() {
      return new FakeNaverUserInfoGateway();
    }
  }

  static final class FakeNaverUserInfoGateway implements NaverUserInfoGateway {

    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<NaverUserInfoException> failure = new AtomicReference<>();

    @Override
    public Map<String, Object> getUserInfo(String providerAccessToken) {
      callCount.incrementAndGet();
      lastToken.set(providerAccessToken);
      NaverUserInfoException currentFailure = failure.get();
      if (currentFailure != null) {
        throw currentFailure;
      }
      return Map.of(
          "resultcode",
          "00",
          "message",
          "success",
          "response",
          Map.of(
              "id", "application-scoped-id",
              "email", "user@example.test",
              "name", "사용자",
              "nickname", "별명",
              "profile_image", "https://profile.example.test/user.png"));
    }

    void reset() {
      callCount.set(0);
      lastToken.set(null);
      failure.set(null);
    }
  }
}
