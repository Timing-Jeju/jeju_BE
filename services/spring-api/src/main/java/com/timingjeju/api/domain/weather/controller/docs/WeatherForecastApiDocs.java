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
        description = "선택자 개수·형식 또는 제주 현지 예보 시각 오류",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "401",
        description = "계획 항목 인증 누락 또는 유효하지 않은 token",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "404",
        description = "공개 장소 또는 소유 계획 항목을 찾을 수 없음",
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
              description = "명시 선택한 공개 지역. 세 selector 중 정확히 하나만 입력합니다.",
              schema = @Schema(pattern = "^[a-z0-9][a-z0-9_-]{0,49}$"))
          String regionCode,
      @Parameter(description = "명시 선택한 공개 장소 canonical UUID", schema = @Schema(format = "uuid"))
          String placeId,
      @Parameter(
              description = "인증 사용자가 소유한 계획 항목 canonical UUID",
              schema = @Schema(format = "uuid"))
          String tripItemId,
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
