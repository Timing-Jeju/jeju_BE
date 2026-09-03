package com.timingjeju.api.domain.schedule.controller.docs;

import com.timingjeju.api.domain.schedule.dto.CreateScheduleItemRequest;
import com.timingjeju.api.domain.schedule.dto.ScheduleMutationResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface ScheduleMutationApiDocs {
  String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

  @Operation(
      operationId = "tripScheduleItemCreate",
      summary = "일정 항목 추가 버전 생성",
      description = "활성 일정을 복사하고 항목과 인접 이동 구간을 검증한 새 user_edit 버전을 원자 활성화합니다.")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = CreateScheduleItemRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        headers = {
          @Header(name = "ETag", schema = @Schema(type = "string")),
          @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean"))
        },
        content = @Content(schema = @Schema(implementation = ScheduleMutationResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "401",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "404",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "409",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<byte[]> addItem(
      @Parameter(
              required = true,
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true) String ifMatch,
      @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN))
          String idempotencyKey,
      byte[] body,
      @Parameter(hidden = true) HttpServletRequest servletRequest);
}
