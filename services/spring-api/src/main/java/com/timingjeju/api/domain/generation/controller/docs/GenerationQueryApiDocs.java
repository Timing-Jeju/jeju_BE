package com.timingjeju.api.domain.generation.controller.docs;

import com.timingjeju.api.domain.generation.dto.GenerationRunResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface GenerationQueryApiDocs {
  @Operation(
      operationId = "getScheduleGeneration",
      summary = "Day 일정 생성 결과 조회",
      description = "소유한 작업의 저장 상태만 조회합니다. query와 본문은 허용하지 않습니다. 종료 작업은 7일간 조회됩니다.")
  @Parameter(
      name = "tripId",
      in = ParameterIn.PATH,
      required = true,
      schema = @Schema(type = "string", format = "uuid"))
  @Parameter(
      name = "runId",
      in = ParameterIn.PATH,
      required = true,
      schema = @Schema(type = "string", format = "uuid"))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "저장된 생성 상태. Retry-After는 queued/running에서만 반환합니다.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = GenerationRunResponse.class)),
        headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"))),
    @ApiResponse(
        responseCode = "400",
        description = "경로, query 또는 본문 오류",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "401",
        description = "인증 필요",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "404",
        description = "소유한 생성 작업 없음",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "410",
        description = "결과 조회 보존 기한 만료",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        description = "저장 결과 조회 불가",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<GenerationRunResponse> read(
      String tripId, String runId, @Parameter(hidden = true) HttpServletRequest request);
}
