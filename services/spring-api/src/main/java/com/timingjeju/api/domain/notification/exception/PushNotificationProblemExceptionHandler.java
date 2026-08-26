package com.timingjeju.api.domain.notification.exception;

import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.domain.notification.controller.PushNotificationController;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PushNotificationController.class)
public final class PushNotificationProblemExceptionHandler {

  private final ProblemResponseWriter writer;

  public PushNotificationProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(PushNotificationException.class)
  void handle(
      PushNotificationException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  void handleUnreadable(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, "INVALID_PUSH_NOTIFICATION_REQUEST");
  }
}
