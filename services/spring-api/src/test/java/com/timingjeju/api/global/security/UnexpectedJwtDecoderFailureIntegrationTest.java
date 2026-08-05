package com.timingjeju.api.global.security;

import static com.timingjeju.api.support.http.ProblemDetailsAssertions.problemDetails;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.timingjeju.api.global.error.ProblemResponseWriter;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000"
    })
@AutoConfigureMockMvc
@Import(UnexpectedJwtDecoderFailureIntegrationTest.FaultDecoderConfiguration.class)
class UnexpectedJwtDecoderFailureIntegrationTest {

  private static final String SENSITIVE_INTERNAL_MESSAGE =
      "https://jwks.example.test/keys?token=should-never-appear";

  @Autowired private MockMvc mockMvc;
  @Autowired private ProblemResponseWriter responseWriter;

  @Test
  void 예상하지_못한_decoder_내부_장애는_401로_숨기지_않고_안전한_500을_반환한다() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/internal-fault")
                .header(HttpHeaders.AUTHORIZATION, "Bearer signed-looking-token"))
        .andExpectAll(
            problemDetails(
                500,
                "https://api.timing-jeju.example/problems/auth-internal-error",
                "내부 서버 오류가 발생했습니다.",
                "AUTH_INTERNAL_ERROR",
                "인증 처리 중 내부 오류가 발생했습니다."))
        .andExpect(content().string(not(containsString(SENSITIVE_INTERNAL_MESSAGE))));
  }

  @Test
  void 이미_committed된_보안_오류_응답에는_본문을_중복해_쓰지_않는다() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.getWriter().write("existing-response");
    response.flushBuffer();

    responseWriter.write(
        new MockHttpServletRequest("GET", "/api/v1/internal-fault"),
        response,
        "AUTH_INTERNAL_ERROR");

    org.assertj.core.api.Assertions.assertThat(response.getContentAsString())
        .isEqualTo("existing-response");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FaultDecoderConfiguration {

    @Bean
    @Primary
    JwtDecoder faultJwtDecoder() {
      return token -> {
        throw new JwtException(
            SENSITIVE_INTERNAL_MESSAGE, new IllegalStateException("provider-internal-fault"));
      };
    }
  }
}
