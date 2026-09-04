package com.timingjeju.api.domain.schedule.controller.docs;

import com.timingjeju.api.domain.schedule.dto.CreateScheduleItemRequest;
import com.timingjeju.api.domain.schedule.dto.ScheduleMutationResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface ScheduleMutationApiDocs {
  String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  String TRIP_ID = "50000000-0000-4000-8000-000000000001";
  String TRACE_ID = "0123456789abcdef0123456789abcdef";
  String REQUEST_EXAMPLE =
      "{\"expectedActiveScheduleVersionId\":\"60000000-0000-4000-8000-000000000001\","
          + "\"dayNo\":1,\"sequenceNo\":2,\"itemType\":\"place_visit\","
          + "\"placeId\":\"20000000-0000-4000-8000-000000000001\","
          + "\"accommodationId\":\"70000000-0000-4000-8000-000000000001\","
          + "\"transportEventId\":\"71000000-0000-4000-8000-000000000001\","
          + "\"title\":\"성산일출봉 방문\",\"plannedStartAt\":\"2026-10-01T11:00:00+09:00\","
          + "\"stayMinutes\":60,\"bufferAfterMinutes\":10,\"required\":true,"
          + "\"memo\":\"정상 도착 후 입장\"}";
  String SUCCESS_EXAMPLE =
      "{\"tripId\":\"50000000-0000-4000-8000-000000000001\","
          + "\"previousScheduleVersionId\":\"60000000-0000-4000-8000-000000000001\","
          + "\"activeScheduleVersionId\":\"60000000-0000-4000-8000-000000000002\","
          + "\"versionNo\":2,\"sourceType\":\"user_edit\",\"feasibilityStale\":true,"
          + "\"changedItemIds\":[\"61000000-0000-4000-8000-000000000002\"],"
          + "\"etag\":\"\\\"trip-50000000-0000-4000-8000-000000000001-r2\\\"\","
          + "\"updatedAt\":\"2026-10-01T09:30:00+09:00\"}";
  String PROBLEM_400 =
      "{\"type\":\"https://api.timing-jeju.com/problems/invalid-request\","
          + "\"title\":\"요청 값이 올바르지 않습니다\",\"status\":400,"
          + "\"detail\":\"필수값과 형식을 확인해 주세요.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"INVALID_REQUEST\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROBLEM_401 =
      "{\"type\":\"https://api.timing-jeju.com/problems/authentication-required\","
          + "\"title\":\"인증이 필요합니다\",\"status\":401,"
          + "\"detail\":\"Bearer access token을 입력해 주세요.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"AUTHENTICATION_REQUIRED\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROBLEM_404 =
      "{\"type\":\"https://api.timing-jeju.com/problems/schedule-version-not-found\","
          + "\"title\":\"일정 버전을 찾을 수 없습니다\",\"status\":404,"
          + "\"detail\":\"요청한 일정 버전이 없거나 접근할 수 없습니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"SCHEDULE_VERSION_NOT_FOUND\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROBLEM_409 =
      "{\"type\":\"https://api.timing-jeju.com/problems/active-schedule-version-conflict\","
          + "\"title\":\"활성 일정이 이미 변경되었습니다\",\"status\":409,"
          + "\"detail\":\"최신 활성 일정을 조회한 뒤 다시 편집해 주세요.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"ACTIVE_SCHEDULE_VERSION_CONFLICT\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROBLEM_422 =
      "{\"type\":\"https://api.timing-jeju.com/problems/schedule-item-invalid\","
          + "\"title\":\"일정 항목을 적용할 수 없습니다\",\"status\":422,"
          + "\"detail\":\"항목 유형별 필수값과 Day 시간 범위를 확인해 주세요.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"SCHEDULE_ITEM_INVALID\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";

  @Operation(
      operationId = "tripScheduleItemCreate",
      tags = "일정",
      summary = "일정 항목 추가 버전 생성",
      description = "활성 일정을 복사하고 항목과 인접 이동 구간을 검증한 새 user_edit 버전을 원자 활성화합니다.")
  @RequestBody(
      required = true,
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CreateScheduleItemRequest.class),
              examples = @ExampleObject(name = "placeVisit", value = REQUEST_EXAMPLE)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "새 user_edit 일정 버전 생성 완료",
        headers = {
          @Header(
              name = "ETag",
              description = "다음 여행 변경 요청에 사용할 새 strong aggregate ETag",
              example = "\"trip-50000000-0000-4000-8000-000000000001-r2\"",
              schema =
                  @Schema(
                      type = "string",
                      pattern =
                          "^\\\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\\\"$")),
          @Header(
              name = "Idempotency-Replayed",
              description = "저장된 완료 응답 replay 여부",
              example = "false",
              schema = @Schema(type = "boolean"))
        },
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ScheduleMutationResponse.class),
                examples = @ExampleObject(name = "created", value = SUCCESS_EXAMPLE))),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples = @ExampleObject(name = "INVALID_REQUEST", value = PROBLEM_400))),
    @ApiResponse(
        responseCode = "401",
        description = "AUTHENTICATION_REQUIRED",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = PROBLEM_401))),
    @ApiResponse(
        responseCode = "404",
        description = "SCHEDULE_VERSION_NOT_FOUND",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(name = "SCHEDULE_VERSION_NOT_FOUND", value = PROBLEM_404))),
    @ApiResponse(
        responseCode = "409",
        description = "ACTIVE_SCHEDULE_VERSION_CONFLICT",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "ACTIVE_SCHEDULE_VERSION_CONFLICT",
                        value = PROBLEM_409))),
    @ApiResponse(
        responseCode = "422",
        description = "SCHEDULE_ITEM_INVALID",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples = @ExampleObject(name = "SCHEDULE_ITEM_INVALID", value = PROBLEM_422)))
  })
  ResponseEntity<byte[]> addItem(
      @Parameter(
              required = true,
              description = "일정을 편집할 소유 여행의 lowercase canonical UUID",
              example = TRIP_ID,
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              description = "현재 여행 revision의 strong aggregate ETag",
              example = "\"trip-50000000-0000-4000-8000-000000000001-r1\"",
              schema =
                  @Schema(
                      type = "string",
                      pattern =
                          "^\\\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\\\"$"))
          String ifMatch,
      @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = true,
              description = "이 일정 변경 요청을 24시간 식별하는 lowercase canonical UUID",
              example = "45000000-0000-4000-8000-000000000050",
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN))
          String idempotencyKey,
      byte[] body,
      @Parameter(hidden = true) HttpServletRequest servletRequest);
}
