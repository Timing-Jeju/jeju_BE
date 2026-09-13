package com.timingjeju.api.domain.generation.controller;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.global.error.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = GenerationController.class)
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
    String[] content =
        switch (code) {
          case "INVALID_PATH_PARAMETER" ->
              new String[] {"400", "경로 값이 올바르지 않습니다", "요청 경로의 식별자를 올바른 UUID로 입력해 주세요."};
          case "INVALID_ASYNC_RUN_REQUEST" ->
              new String[] {"400", "비동기 요청이 올바르지 않습니다", "요청 필드와 형식을 확인해 주세요."};
          case "IF_MATCH_REQUIRED" ->
              new String[] {"400", "일정 버전 조건이 필요합니다", "최신 여행의 strong ETag를 If-Match에 입력해 주세요."};
          case "IF_MATCH_INVALID" ->
              new String[] {"400", "일정 버전 조건이 올바르지 않습니다", "최신 여행의 strong ETag를 If-Match에 입력해 주세요."};
          case "IDEMPOTENCY_KEY_REQUIRED" ->
              new String[] {"400", "멱등성 키가 필요합니다", "Idempotency-Key 헤더를 입력해 주세요."};
          case "IDEMPOTENCY_KEY_INVALID" ->
              new String[] {
                "400", "멱등성 키가 올바르지 않습니다", "1~128자 printable ASCII Idempotency-Key를 입력해 주세요."
              };
          case "IDEMPOTENCY_KEY_REUSED" ->
              new String[] {"409", "멱등성 키를 다시 사용할 수 없습니다", "새 Idempotency-Key로 다시 요청해 주세요."};
          case "TRIP_NOT_FOUND" -> new String[] {"404", "여행을 찾을 수 없습니다", "요청한 여행을 찾을 수 없습니다."};
          case "TRIP_VERSION_CONFLICT" ->
              new String[] {"409", "여행 버전이 변경되었습니다", "최신 여행을 조회한 뒤 다시 시도해 주세요."};
          case "ACTIVE_SCHEDULE_VERSION_CONFLICT" ->
              new String[] {"409", "일정 버전이 변경되었습니다", "최신 일정을 다시 확인한 뒤 후보를 적용해 주세요."};
          case "ACTIVE_RUN_CONFLICT" ->
              new String[] {"409", "진행 중인 계산이 있습니다", "기존 계산이 끝난 뒤 다시 요청해 주세요."};
          case "GENERATION_INPUT_CONSTRAINT_VIOLATION" ->
              new String[] {"422", "일정 생성 조건을 사용할 수 없습니다", "여행 기간과 생성 조건을 확인해 주세요."};
          case "ASYNC_INTAKE_UNAVAILABLE" ->
              new String[] {"503", "계산 요청을 접수할 수 없습니다", "요청을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."};
          default -> null;
        };
    return content == null
        ? null
        : new ProblemDefinition(
            URI.create(
                "https://api.timing-jeju.com/problems/"
                    + code.toLowerCase(Locale.ROOT).replace('_', '-')),
            content[1],
            Integer.parseInt(content[0]),
            code,
            content[2]);
  }
}
