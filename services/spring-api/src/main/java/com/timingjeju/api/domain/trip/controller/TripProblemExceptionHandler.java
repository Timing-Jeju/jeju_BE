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
  private final TripPreferencesProblemWriter preferencesWriter;

  public TripProblemExceptionHandler(
      ProblemResponseWriter writer, TripPreferencesProblemWriter preferencesWriter) {
    this.writer = writer;
    this.preferencesWriter = preferencesWriter;
  }

  @ExceptionHandler(TripException.class)
  void handleTrip(TripException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (isPreferencesRequest(request)) {
      preferencesWriter.write(request, response, failure.code());
    } else {
      writer.write(request, response, failure.code());
    }
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

  private static boolean isPreferencesRequest(HttpServletRequest request) {
    return request.getRequestURI().matches(".*/api/v1/trips/[^/]+/preferences$");
  }
}
