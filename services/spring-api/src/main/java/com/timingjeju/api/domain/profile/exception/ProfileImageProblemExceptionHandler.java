package com.timingjeju.api.domain.profile.exception;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.domain.profile.controller.ProfileImageController;
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
@RestControllerAdvice(assignableTypes = ProfileImageController.class)
public final class ProfileImageProblemExceptionHandler {

  private final ProblemResponseWriter writer;

  public ProfileImageProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ProfileImageException.class)
  void handleProfileImage(
      ProfileImageException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(CurrentUserProfileException.class)
  void handleProfileData(
      CurrentUserProfileException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(ProfileProvisioningException.class)
  void handleProvisioning(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(
        request,
        response,
        "GET".equals(request.getMethod())
            ? "PROFILE_DATA_UNAVAILABLE"
            : "PROFILE_IMAGE_STORAGE_UNAVAILABLE");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  void handleUnreadable(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, "INVALID_PROFILE_IMAGE_REQUEST");
  }

  @ExceptionHandler(IdempotencyException.class)
  void handleIdempotency(
      IdempotencyException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (exception.retryAfterSeconds().isPresent()) {
      response.setHeader(
          "Retry-After", Integer.toString(exception.retryAfterSeconds().orElseThrow()));
      writer.write(request, response, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
      return;
    }
    String code =
        exception.status() == 409
            ? "IDEMPOTENCY_PAYLOAD_CONFLICT"
            : "INVALID_PROFILE_IMAGE_REQUEST";
    writer.write(request, response, code);
  }
}
