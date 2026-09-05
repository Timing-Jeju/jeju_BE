package com.timingjeju.api.domain.accommodation.controller;

import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.domain.accommodation.exception.AccommodationProblemDefinitions;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AccommodationController.class)
public final class AccommodationProblemExceptionHandler {
  private final ProblemResponseWriter writer;
  private final AccommodationProblemDefinitions definitions;

  public AccommodationProblemExceptionHandler(
      ProblemResponseWriter writer, AccommodationProblemDefinitions definitions) {
    this.writer = writer;
    this.definitions = definitions;
  }

  @ExceptionHandler(AccommodationException.class)
  void handle(
      AccommodationException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, definitions.find(failure.code()), List.of());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
  void handleMalformed(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, definitions.find("INVALID_REQUEST"), List.of());
  }

  @ExceptionHandler(ProfileProvisioningException.class)
  void handleProvisioning(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, definitions.find("INTERNAL_SERVER_ERROR"), List.of());
  }
}
