package com.timingjeju.api.domain.schedule.controller;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.schedule.ScheduleException;
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
@RestControllerAdvice(
    assignableTypes = {ScheduleController.class, ScheduleMutationController.class})
public final class ScheduleProblemExceptionHandler {
  private final ProblemResponseWriter writer;

  public ScheduleProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ScheduleException.class)
  void handle(ScheduleException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, failure.code());
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
}
