package com.timingjeju.api.domain.auth.controller;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SocialLoginController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NaverUserInfoExceptionHandler {

  private final ProblemResponseWriter responseWriter;

  public NaverUserInfoExceptionHandler(ProblemResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @ExceptionHandler(NaverUserInfoException.class)
  void handle(
      NaverUserInfoException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    responseWriter.write(request, response, exception.code().externalCode());
  }
}
