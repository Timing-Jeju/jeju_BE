package com.timingjeju.api.domain.generation.controller.docs;

import com.timingjeju.api.domain.generation.dto.GenerationAcceptedResponse;
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

public interface GenerationApiDocs {
  @Operation(
      operationId = "createScheduleGeneration",
      summary = "Day 일정 생성 접수",
      description = "저장된 여행 조건을 불변 복사하고 queued 작업만 저장합니다. 후보는 자동 적용하지 않습니다.")
  @Parameter(
      name = "tripId",
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
              schema = @Schema(implementation = CreateRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "202",
        description = "생성 요청 접수",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = GenerationAcceptedResponse.class)),
        headers = {
          @Header(name = "Location", schema = @Schema(type = "string")),
          @Header(name = "Retry-After", schema = @Schema(type = "integer")),
          @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean"))
        }),
    @ApiResponse(
        responseCode = "400",
        description = "요청 또는 필수 헤더 오류",
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
        description = "소유한 여행 없음",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "409",
        description = "버전, 멱등성 또는 진행 중 작업 충돌",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        description = "생성 조건 불완전",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "429",
        description = "요청 한도 초과",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        description = "접수 사용 불가",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<byte[]> create(
      String tripId, @Parameter(hidden = true) HttpServletRequest request);

  @Schema(
      name = "GenerationCreateRequest",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record CreateRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID targetDayId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
          UUID expectedActiveScheduleVersionId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "3", maximum = "3")
          int candidateCount) {}
}
