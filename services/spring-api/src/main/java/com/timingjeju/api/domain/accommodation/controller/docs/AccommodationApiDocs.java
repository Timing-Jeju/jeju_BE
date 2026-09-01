package com.timingjeju.api.domain.accommodation.controller.docs;

import com.timingjeju.api.application.accommodation.AccommodationMutationPayload;
import com.timingjeju.api.domain.accommodation.dto.request.CreateAccommodationRequest;
import com.timingjeju.api.domain.accommodation.dto.request.PatchAccommodationRequest;
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

public interface AccommodationApiDocs {
  String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  String ETAG_PATTERN = "^\\\"trip-[1-9][0-9]*\\\"$";

  @Operation(
      operationId = "createTripAccommodation",
      summary = "여행 숙소 추가",
      description = "여행 ETag를 검사하고 날짜순 sequence를 같은 transaction에서 재구성합니다.")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = CreateAccommodationRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        headers = {
          @Header(name = "Location", schema = @Schema(type = "string", format = "uri")),
          @Header(name = "ETag", schema = @Schema(type = "string", pattern = ETAG_PATTERN)),
          @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean"))
        },
        content = @Content(schema = @Schema(implementation = AccommodationMutationPayload.class))),
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
  ResponseEntity<byte[]> create(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = "^[!-~]{1,128}$"))
          String key,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = ETAG_PATTERN))
          String ifMatch,
      byte[] body,
      @Parameter(hidden = true) HttpServletRequest request);

  @Operation(
      operationId = "updateTripAccommodation",
      summary = "여행 숙소 수정",
      description = "presence semantics와 canonical no-op을 보존해 숙소를 수정합니다.")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = PatchAccommodationRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers = @Header(name = "ETag", schema = @Schema(type = "string", pattern = ETAG_PATTERN)),
        content = @Content(schema = @Schema(implementation = AccommodationMutationPayload.class))),
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
  ResponseEntity<byte[]> patch(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String accommodationId,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = ETAG_PATTERN))
          String ifMatch,
      byte[] body,
      @Parameter(hidden = true) HttpServletRequest request);

  @Operation(
      operationId = "deleteTripAccommodation",
      summary = "여행 숙소 삭제",
      description = "활성 일정이 없고 남은 숙소에 내부 공백이 없을 때 삭제합니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", content = @Content),
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
  ResponseEntity<Void> delete(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String accommodationId,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = ETAG_PATTERN))
          String ifMatch,
      @Parameter(hidden = true) byte[] body,
      @Parameter(hidden = true) HttpServletRequest request);
}
