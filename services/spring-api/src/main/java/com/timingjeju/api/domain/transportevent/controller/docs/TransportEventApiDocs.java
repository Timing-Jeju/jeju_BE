package com.timingjeju.api.domain.transportevent.controller.docs;

import com.timingjeju.api.application.transportevent.TransportEventMutationPayload;
import com.timingjeju.api.domain.transportevent.dto.request.PutTransportEventRequest;
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

public interface TransportEventApiDocs {
  String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  String ETAG_PATTERN = "^\\\"trip-[0-9a-f-]{36}-r[1-9][0-9]*\\\"$";

  @Operation(
      operationId = "putTripTransportEvent",
      summary = "여행 항공·선박 이벤트 저장",
      description = "도착 또는 출발 이벤트 한 건을 완전 교체하고 일정 stale 정책을 원자 적용합니다.")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = PutTransportEventRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers = @Header(name = "ETag", schema = @Schema(type = "string", pattern = ETAG_PATTERN)),
        content = @Content(schema = @Schema(implementation = TransportEventMutationPayload.class))),
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
  ResponseEntity<TransportEventMutationPayload> put(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = ETAG_PATTERN))
          String ifMatch,
      @Parameter(hidden = true) HttpServletRequest request);

  @Operation(
      operationId = "deleteTripTransportEvent",
      summary = "여행 항공·선박 이벤트 삭제",
      description = "query로 선택한 도착 또는 출발 이벤트만 삭제하고 일정 stale 정책을 반환합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers = @Header(name = "ETag", schema = @Schema(type = "string", pattern = ETAG_PATTERN)),
        content = @Content(schema = @Schema(implementation = TransportEventMutationPayload.class))),
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
  ResponseEntity<TransportEventMutationPayload> delete(
      @Parameter(required = true, schema = @Schema(type = "string", pattern = UUID_PATTERN))
          String tripId,
      @Parameter(
              name = "eventType",
              in = ParameterIn.QUERY,
              required = true,
              schema =
                  @Schema(
                      type = "string",
                      allowableValues = {"arrival", "departure"}))
          String eventType,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = ETAG_PATTERN))
          String ifMatch,
      @Parameter(hidden = true) HttpServletRequest request);
}
