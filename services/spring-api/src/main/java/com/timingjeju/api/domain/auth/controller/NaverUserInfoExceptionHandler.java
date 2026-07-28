package com.timingjeju.api.domain.auth.controller;

import com.timingjeju.api.domain.auth.dto.response.SocialLoginErrorResponse;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SocialLoginController.class)
public class NaverUserInfoExceptionHandler {

  @ExceptionHandler(NaverUserInfoException.class)
  ResponseEntity<SocialLoginErrorResponse> handle(NaverUserInfoException exception) {
    var failureCode = exception.code();
    String traceId = UUID.randomUUID().toString().replace("-", "");
    return ResponseEntity.status(statusFor(failureCode).value())
        .body(
            new SocialLoginErrorResponse(
                failureCode.externalCode(), failureCode.message(), traceId));
  }

  private static HttpStatus statusFor(NaverUserInfoFailureCode failureCode) {
    return switch (failureCode) {
      case PROVIDER_TOKEN_INVALID, UPSTREAM_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case UPSTREAM_FORBIDDEN -> HttpStatus.FORBIDDEN;
      case UPSTREAM_RATE_LIMITED -> HttpStatus.SERVICE_UNAVAILABLE;
      case UPSTREAM_UNAVAILABLE, UPSTREAM_MALFORMED_RESPONSE, UPSTREAM_RESPONSE_TOO_LARGE ->
          HttpStatus.BAD_GATEWAY;
      case UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
      case EMAIL_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
  }
}
