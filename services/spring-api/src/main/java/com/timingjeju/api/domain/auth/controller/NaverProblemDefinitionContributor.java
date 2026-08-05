package com.timingjeju.api.domain.auth.controller;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.util.Arrays;
import java.util.Collection;

public final class NaverProblemDefinitionContributor implements ProblemDefinitionContributor {

  @Override
  public Collection<ProblemDefinition> definitions() {
    return Arrays.stream(NaverUserInfoFailureCode.values())
        .map(NaverProblemDefinitionContributor::definition)
        .toList();
  }

  private static ProblemDefinition definition(NaverUserInfoFailureCode code) {
    return ProblemDefinition.forCode(
        code.externalCode(), titleFor(code), statusFor(code), code.detail());
  }

  private static int statusFor(NaverUserInfoFailureCode code) {
    return switch (code) {
      case PROVIDER_TOKEN_INVALID, UPSTREAM_UNAUTHORIZED -> 401;
      case UPSTREAM_FORBIDDEN -> 403;
      case EMAIL_REQUIRED -> 422;
      case APPLICATION_RATE_LIMITED -> 429;
      case UPSTREAM_UNAVAILABLE, UPSTREAM_MALFORMED_RESPONSE, UPSTREAM_RESPONSE_TOO_LARGE -> 502;
      case APPLICATION_OVERLOADED, UPSTREAM_RATE_LIMITED -> 503;
      case UPSTREAM_TIMEOUT -> 504;
    };
  }

  private static String titleFor(NaverUserInfoFailureCode code) {
    return switch (code) {
      case PROVIDER_TOKEN_INVALID, UPSTREAM_UNAUTHORIZED -> "인증에 실패했습니다.";
      case UPSTREAM_FORBIDDEN -> "외부 서비스 접근이 거부되었습니다.";
      case EMAIL_REQUIRED -> "필수 정보가 누락되었습니다.";
      case APPLICATION_RATE_LIMITED -> "요청이 너무 많습니다.";
      case APPLICATION_OVERLOADED -> "서비스를 일시적으로 사용할 수 없습니다.";
      case UPSTREAM_RATE_LIMITED,
          UPSTREAM_UNAVAILABLE,
          UPSTREAM_MALFORMED_RESPONSE,
          UPSTREAM_RESPONSE_TOO_LARGE,
          UPSTREAM_TIMEOUT ->
          "외부 서비스 요청을 완료하지 못했습니다.";
    };
  }
}
