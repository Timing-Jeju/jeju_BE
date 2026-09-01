package com.timingjeju.api.domain.schedule.controller.docs;

import com.timingjeju.api.domain.schedule.dto.ScheduleResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;

public interface ScheduleApiDocs {
  String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

  @Operation(
      operationId = "tripScheduleRead",
      summary = "활성 또는 명시 일정 버전 조회",
      description = "소유자 범위의 불변 일정 버전과 Day·item·leg·진행 상태를 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = ScheduleResponse.class))),
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
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ScheduleResponse read(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(
              required = false,
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN))
          String versionId,
      @Parameter(hidden = true) HttpServletRequest request);
}
