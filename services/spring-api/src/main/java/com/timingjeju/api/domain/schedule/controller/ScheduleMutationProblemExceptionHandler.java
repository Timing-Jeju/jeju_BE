package com.timingjeju.api.domain.schedule.controller;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.domain.schedule.exception.ScheduleProblemDefinitions;
import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ScheduleMutationController.class)
public final class ScheduleMutationProblemExceptionHandler {
  private final ProblemResponseWriter writer;

  public ScheduleMutationProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ScheduleException.class)
  void handle(ScheduleException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(request, response, failure.code());
  }

  @ExceptionHandler(TripException.class)
  void handleTrip(TripException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(request, response, failure.code());
  }

  @ExceptionHandler(IdempotencyException.class)
  void handleIdempotency(
      IdempotencyException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    failure
        .retryAfterSeconds()
        .ifPresent(seconds -> response.setHeader("Retry-After", String.valueOf(seconds)));
    write(request, response, failure.code());
  }

  private void write(HttpServletRequest request, HttpServletResponse response, String code)
      throws IOException {
    String canonicalCode =
        List.of("IF_MATCH_REQUIRED", "INVALID_IF_MATCH").contains(code) ? "INVALID_REQUEST" : code;
    ProblemDefinition definition = ScheduleProblemDefinitions.mutationDefinition(canonicalCode);
    if (definition == null) {
      writer.write(request, response, code);
      return;
    }
    writer.write(request, response, definition, List.of());
  }
}
