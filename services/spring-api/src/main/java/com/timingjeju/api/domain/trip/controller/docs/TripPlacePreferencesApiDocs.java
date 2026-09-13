package com.timingjeju.api.domain.trip.controller.docs;

import com.timingjeju.api.domain.trip.dto.response.TripPlacePreferencesResponse;
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

public interface TripPlacePreferencesApiDocs {
  String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

  @Operation(
      operationId = "tripPlacePreferencesUpdate",
      summary = "여행 희망·회피 장소 전체 교체",
      description = "찜 여부와 무관한 유효 canonical 장소로 필수·선택·회피 목록과 날짜별 체류시간을 원자적으로 전체 교체합니다.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          com.timingjeju.api.domain.trip.dto.request
                              .UpdateTripPlacePreferencesRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers =
            @Header(
                name = "ETag",
                schema = @Schema(type = "string", pattern = "^\\\"[A-Za-z0-9._:-]{1,128}\\\"$")),
        content = @Content(schema = @Schema(implementation = TripPlacePreferencesResponse.class))),
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
  ResponseEntity<TripPlacePreferencesResponse> replace(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = "^\\\"[A-Za-z0-9._:-]{1,128}\\\"$"))
          String ifMatch,
      @Parameter(hidden = true) HttpServletRequest request);
}
