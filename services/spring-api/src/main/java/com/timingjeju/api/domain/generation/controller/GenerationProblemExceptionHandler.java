package com.timingjeju.api.domain.generation.controller;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.global.error.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
      GenerationController.class,
      GenerationQueryController.class,
      GenerationApplyController.class
    })
public final class GenerationProblemExceptionHandler {
  private final ProblemResponseWriter writer;

  public GenerationProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler({
    GenerationException.class,
    TripException.class,
    ScheduleException.class,
    IdempotencyException.class
  })
  void handle(RuntimeException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String code =
        switch (failure) {
          case GenerationException e -> e.code();
          case TripException e -> e.code();
          case ScheduleException e -> e.code();
          case IdempotencyException e -> e.code();
          default -> throw new IllegalArgumentException("unsupported exception");
        };
    if (failure instanceof IdempotencyException e)
      e.retryAfterSeconds()
          .ifPresent(seconds -> response.setHeader("Retry-After", Integer.toString(seconds)));
    if (Set.of("GENERATION_INPUT_UNAVAILABLE", "TRIP_DATA_UNAVAILABLE").contains(code))
      code = "ASYNC_INTAKE_UNAVAILABLE";
    var definition = definition(code);
    if (definition == null) {
      writer.write(request, response, "INTERNAL_SERVER_ERROR");
      return;
    }
    writer.write(request, response, definition, fieldErrors(code));
  }

  private static List<FieldErrorDetail> fieldErrors(String code) {
    FieldErrorDetail error =
        switch (code) {
          case "INVALID_QUERY_PARAMETER" -> new FieldErrorDetail("query", "조회 query는 허용하지 않습니다.");
          case "REQUEST_BODY_NOT_ALLOWED" -> new FieldErrorDetail("body", "조회 본문은 허용하지 않습니다.");
          case "INVALID_PATH_PARAMETER" -> new FieldErrorDetail("path", "canonical UUID가 필요합니다.");
          case "INVALID_ASYNC_RUN_REQUEST" ->
              new FieldErrorDetail("body", "closed request schema와 일치해야 합니다.");
          case "IF_MATCH_REQUIRED" -> new FieldErrorDetail("If-Match", "필수 헤더입니다.");
          case "IF_MATCH_INVALID" -> new FieldErrorDetail("If-Match", "strong Trip ETag가 필요합니다.");
          case "IDEMPOTENCY_KEY_REQUIRED" -> new FieldErrorDetail("Idempotency-Key", "필수 헤더입니다.");
          case "IDEMPOTENCY_KEY_INVALID" ->
              new FieldErrorDetail("Idempotency-Key", "1~128자 printable ASCII여야 합니다.");
          default -> null;
        };
    return error == null ? List.of() : List.of(error);
  }

  static ProblemDefinition definition(String code) {
    return com.timingjeju.api.domain.generation.exception.GenerationProblemDefinitions.definition(
        code);
  }
}
