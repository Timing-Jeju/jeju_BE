package com.timingjeju.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NaverUserInfoServiceTest {

  @Test
  void Naver_중첩_응답을_표준_UserInfo로_평탄화한다() {
    NaverUserInfoService service =
        new NaverUserInfoService(
            ignored ->
                Map.of(
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
                        "profile_image", "https://profile.example.test/user.png",
                        "mobile", "not-returned")));

    NaverStandardUserInfo userInfo = service.getUserInfo("opaque-provider-token");

    assertThat(userInfo.sub()).isEqualTo("application-scoped-id");
    assertThat(userInfo.email()).isEqualTo("user@example.test");
    assertThat(userInfo.name()).isEqualTo("사용자");
    assertThat(userInfo.preferredUsername()).isEqualTo("별명");
    assertThat(userInfo.picture()).isEqualTo("https://profile.example.test/user.png");
  }

  @Test
  void email_동의가_없으면_fail_closed하고_원본_프로필을_노출하지_않는다() {
    NaverUserInfoService service =
        new NaverUserInfoService(
            ignored ->
                Map.of(
                    "resultcode",
                    "00",
                    "message",
                    "success",
                    "response",
                    Map.of("id", "application-scoped-id", "nickname", "별명")));

    assertThatThrownBy(() -> service.getUserInfo("opaque-provider-token"))
        .isInstanceOf(NaverUserInfoException.class)
        .extracting(exception -> ((NaverUserInfoException) exception).code().name())
        .isEqualTo("EMAIL_REQUIRED");
  }

  @Test
  void 필수_id가_없거나_타입이_잘못된_응답은_안전하게_거부한다() {
    NaverUserInfoService missingId =
        new NaverUserInfoService(
            ignored ->
                Map.of(
                    "resultcode",
                    "00",
                    "message",
                    "success",
                    "response",
                    Map.of("email", "user@example.test")));
    NaverUserInfoService invalidResponse =
        new NaverUserInfoService(ignored -> Map.of("response", "unexpected"));

    assertThatThrownBy(() -> missingId.getUserInfo("opaque-provider-token"))
        .isInstanceOf(NaverUserInfoException.class);
    assertThatThrownBy(() -> invalidResponse.getUserInfo("opaque-provider-token"))
        .isInstanceOf(NaverUserInfoException.class);
  }

  @Test
  void 성공_envelope의_resultcode와_message를_정확한_문자열로_검증한다() {
    Map<String, Object> profile =
        Map.of("response", Map.of("id", "application-scoped-id", "email", "user@example.test"));

    for (Map<String, Object> invalidPayload :
        java.util.List.of(
            profile,
            Map.of(
                "resultcode",
                0,
                "message",
                "success",
                "response",
                Map.of("id", "application-scoped-id", "email", "user@example.test")),
            Map.of(
                "resultcode",
                "00",
                "message",
                true,
                "response",
                Map.of("id", "application-scoped-id", "email", "user@example.test")),
            Map.of(
                "resultcode",
                "01",
                "message",
                "success",
                "response",
                Map.of("id", "application-scoped-id", "email", "user@example.test")),
            Map.of(
                "resultcode",
                "00",
                "message",
                "SUCCESS",
                "response",
                Map.of("id", "application-scoped-id", "email", "user@example.test")))) {
      NaverUserInfoService service = new NaverUserInfoService(ignored -> invalidPayload);

      assertThatThrownBy(() -> service.getUserInfo("opaque-provider-token"))
          .isInstanceOf(NaverUserInfoException.class)
          .extracting(exception -> ((NaverUserInfoException) exception).code().name())
          .isEqualTo("UPSTREAM_MALFORMED_RESPONSE");
    }
  }
}
