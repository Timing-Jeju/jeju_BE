package com.timingjeju.api.domain.places.controller.docs;

import com.timingjeju.api.domain.places.dto.response.PlacesListResponse;
import com.timingjeju.api.domain.places.model.CanonicalPlaceCategory;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public interface PlacesApiDocs {

  @Operation(
      summary = "관광지 검색·필터 목록",
      description = "정규화된 제주 관광지 read model을 안정적인 HMAC keyset cursor로 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "관광지 cursor page",
        content = @Content(schema = @Schema(implementation = PlacesListResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "검색·위치·cursor 조건 오류",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "401",
        description = "유효하지 않은 token 또는 익명 savedOnly 요청",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        description = "검색 도메인 제약 위반",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        description = "안전한 정규화 장소 데이터 사용 불가",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  PlacesListResponse list(
      @Parameter(schema = @Schema(minLength = 1, maxLength = 100)) String query,
      @Pattern(regexp = CanonicalPlaceCategory.OPEN_API_PATTERN) String category,
      @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,49}$") String regionCode,
      @DecimalMin("33.0") @DecimalMax("34.0") Double lat,
      @DecimalMin("126.0") @DecimalMax("127.0") Double lng,
      @Min(100) @Max(50000) Integer radiusMeters,
      @Size(min = 1, max = 2048) String cursor,
      @Min(1) @Max(100) Integer size,
      Boolean savedOnly);
}
