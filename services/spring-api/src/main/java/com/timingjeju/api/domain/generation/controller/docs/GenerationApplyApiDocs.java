package com.timingjeju.api.domain.generation.controller.docs;

import com.timingjeju.api.domain.generation.dto.GenerationAppliedResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

public interface GenerationApplyApiDocs {
  @Operation(
      operationId = "applyScheduleGenerationCandidate",
      summary = "Day 생성 후보 적용",
      description = "저장 후보를 원자 적용하며 같은 멱등성 키의 재시도는 기존 응답을 반환합니다.")
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
  @Parameter(
      name = "candidateId",
      in = ParameterIn.PATH,
      required = true,
      schema = @Schema(type = "string", format = "uuid"))
  @Parameter(
      name = "If-Match",
      in = ParameterIn.HEADER,
      required = true,
      schema = @Schema(type = "string"))
  @Parameter(
      name = "Idempotency-Key",
      in = ParameterIn.HEADER,
      required = true,
      schema = @Schema(type = "string", minLength = 1, maxLength = 128, pattern = "^[ -~]+$"))
  @RequestBody(
      required = true,
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApplyRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "선택 후보 적용 또는 멱등 응답 재생",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = GenerationAppliedResponse.class)),
        headers = {
          @Header(name = "ETag", schema = @Schema(type = "string")),
          @Header(name = "Location", schema = @Schema(type = "string")),
          @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean"))
        }),
    @ApiResponse(
        responseCode = "400",
        description = "경로·본문·필수 헤더 오류",
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
        description = "소유한 여행·작업·후보 없음",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "409",
        description = "버전·멱등성·후보 상태 충돌",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "410",
        description = "후보 만료 또는 근거 복원 불가",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        description = "적용할 수 없는 후보",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        description = "결과 저장소 일시 장애",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<byte[]> apply(
      String tripId,
      String runId,
      String candidateId,
      @Parameter(hidden = true) HttpServletRequest request);

  @Schema(
      name = "ApplyCandidateRequest",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
      requiredProperties = {"expectedActiveScheduleVersionId"})
  record ApplyRequest(@Schema(nullable = true) UUID expectedActiveScheduleVersionId) {}
}
