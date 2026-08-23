package com.timingjeju.api.domain.weather.controller.docs;

import com.timingjeju.api.domain.weather.dto.response.WeatherForecastResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

public interface WeatherForecastApiDocs {

  @Operation(
      summary = "날씨 예보 공개 조회",
      description = "저장된 KMA 정규화 예보를 제주 격자·발표 base·freshness 계약으로 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "날씨 예보",
        content = @Content(schema = @Schema(implementation = WeatherForecastResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "위경도 또는 제주 현지 예보 시각 형식 오류",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "401",
        description = "전달한 선택 인증 token이 유효하지 않음",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        description = "지원하지 않는 제주 위치 또는 예보 기간",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        description = "최신·직전 정규화 예보를 사용할 수 없음",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  WeatherForecastResponse forecast(
      @Parameter(
              required = true,
              schema =
                  @Schema(type = "number", exclusiveMinimumValue = -90, exclusiveMaximumValue = 90))
          @DecimalMin(value = "-90", inclusive = false)
          @DecimalMax(value = "90", inclusive = false)
          String lat,
      @Parameter(
              required = true,
              schema = @Schema(type = "number", minimum = "-180", maximum = "180"))
          @DecimalMin("-180")
          @DecimalMax("180")
          String lng,
      @Parameter(
              required = true,
              schema =
                  @Schema(
                      type = "string",
                      format = "date-time",
                      pattern = "^\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):00:00\\+09:00$"))
          String dateTime,
      @Parameter(hidden = true) HttpServletRequest request);
}
