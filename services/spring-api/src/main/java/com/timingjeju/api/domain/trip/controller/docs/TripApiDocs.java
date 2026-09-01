package com.timingjeju.api.domain.trip.controller.docs;

import com.timingjeju.api.domain.trip.dto.response.TripAggregateResponse;
import com.timingjeju.api.domain.trip.dto.response.TripListResponse;
import com.timingjeju.api.domain.trip.dto.response.TripPreferencesResponse;
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

public interface TripApiDocs {
  String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

  @Operation(summary = "내 여행 목록 조회", description = "소유자 범위에서 updatedAt 내림차순 keyset cursor로 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = TripListResponse.class))),
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
        responseCode = "503",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  TripListResponse list(
      @Parameter(
              schema =
                  @Schema(
                      type = "string",
                      allowableValues = {
                        "draft",
                        "generating",
                        "planned",
                        "live",
                        "completed",
                        "cancelled",
                        "failed"
                      }))
          String status,
      @Parameter(schema = @Schema(type = "string", allowableValues = "updated_at_desc"))
          String sort,
      @Parameter(schema = @Schema(type = "string", minLength = 1, maxLength = 2048)) String cursor,
      @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50")) Integer size,
      @Parameter(hidden = true) HttpServletRequest request);

  @Operation(summary = "여행 생성", description = "여행과 날짜별 Day를 하나의 트랜잭션으로 생성합니다.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          com.timingjeju.api.domain.trip.dto.request.CreateTripRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        headers = {
          @Header(name = "Location", schema = @Schema(type = "string", format = "uri")),
          @Header(
              name = "ETag",
              schema = @Schema(type = "string", pattern = "^\\\"[A-Za-z0-9._:-]{1,128}\\\"$")),
          @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean"))
        },
        content = @Content(schema = @Schema(implementation = TripAggregateResponse.class))),
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
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<byte[]> create(
      @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = true,
              description = "여행 생성 요청을 24시간 동안 식별하는 lowercase canonical UUID입니다.",
              example = "44000000-0000-0000-0000-000000000044",
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN))
          String idempotencyKey,
      byte[] body);

  @Operation(summary = "여행 상세 조회", description = "소유자 조건을 SQL에 포함해 IDOR를 차단합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = TripAggregateResponse.class))),
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
        responseCode = "503",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  TripAggregateResponse read(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId);

  @Operation(summary = "여행 선호·교통수단 전체 교체", description = "강한 If-Match로 선호와 1~3순위 교통수단을 원자 교체합니다.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          com.timingjeju.api.domain.trip.dto.request.UpdateTripPreferencesRequest
                              .class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers =
            @Header(
                name = "ETag",
                schema = @Schema(type = "string", pattern = "^\\\"[A-Za-z0-9._:-]{1,128}\\\"$")),
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TripPreferencesResponse.class))),
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
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<TripPreferencesResponse> replacePreferences(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = "^\\\"[A-Za-z0-9._:-]{1,128}\\\"$"))
          String ifMatch,
      byte[] body,
      @Parameter(hidden = true) HttpServletRequest request);
}
