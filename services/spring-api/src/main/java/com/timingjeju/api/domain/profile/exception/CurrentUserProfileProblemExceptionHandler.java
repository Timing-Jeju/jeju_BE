package com.timingjeju.api.domain.profile.exception;

import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.domain.profile.controller.CurrentUserProfileController;
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
@RestControllerAdvice(assignableTypes = CurrentUserProfileController.class)
public final class CurrentUserProfileProblemExceptionHandler {

  private final ProblemResponseWriter writer;

  public CurrentUserProfileProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(CurrentUserProfileException.class)
  void handleProfile(
      CurrentUserProfileException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(ProfileProvisioningException.class)
  void handleProvisioning(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, "PROFILE_DATA_UNAVAILABLE");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  void handleUnreadable(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, "INVALID_PROFILE_LEGAL_REQUEST");
  }
}
