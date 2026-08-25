package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TripController.class)
public class TripProblemExceptionHandler {
  private final ProblemResponseWriter writer;

  public TripProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(TripException.class)
  void handleTrip(TripException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, failure.code());
  }

  @ExceptionHandler(IdempotencyException.class)
  void handleIdempotency(
      IdempotencyException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    failure
        .retryAfterSeconds()
        .ifPresent(seconds -> response.setHeader("Retry-After", String.valueOf(seconds)));
    writer.write(request, response, failure.code());
  }

  @ExceptionHandler(ProfileProvisioningException.class)
  void handleProvisioning(
      ProfileProvisioningException failure,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    String publicCode =
        switch (failure.code()) {
          case EMAIL_OWNERSHIP_CONFLICT, PROVIDER_SUBJECT_CONFLICT -> "PROFILE_CONFLICT";
          case INVALID_AUTH_IDENTITY, STORAGE_UNAVAILABLE -> "TRIP_DATA_UNAVAILABLE";
        };
    writer.write(request, response, publicCode);
  }
}
