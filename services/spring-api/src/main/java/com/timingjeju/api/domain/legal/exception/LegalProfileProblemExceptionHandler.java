package com.timingjeju.api.domain.legal.exception;

import com.timingjeju.api.application.legal.LegalProfileException;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.domain.legal.controller.LegalProfileController;
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
@RestControllerAdvice(assignableTypes = LegalProfileController.class)
public final class LegalProfileProblemExceptionHandler {

  private final ProblemResponseWriter writer;

  public LegalProfileProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(LegalProfileException.class)
  void handleLegal(
      LegalProfileException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  void handleUnreadable(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, "INVALID_PROFILE_LEGAL_REQUEST");
  }

  @ExceptionHandler(ProfileProvisioningException.class)
  void handleProvisioning(
      ProfileProvisioningException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    String publicCode =
        switch (exception.code()) {
          case EMAIL_OWNERSHIP_CONFLICT, PROVIDER_SUBJECT_CONFLICT -> "PROFILE_CONFLICT";
          case INVALID_AUTH_IDENTITY, STORAGE_UNAVAILABLE -> "PROFILE_DATA_UNAVAILABLE";
        };
    writer.write(request, response, publicCode);
  }
}
