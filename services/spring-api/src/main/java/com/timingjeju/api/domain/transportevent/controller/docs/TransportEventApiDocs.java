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
      parameters =
          @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = false,
              description =
                  "새 FE 저장 흐름은 canonical UUID 키를 사용합니다. 같은 키·본문 재시도는 원래 응답과 ETag를 반환하며 키 없는 기존 호출도 지원합니다.",
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN),
              example = "53000000-0000-4000-8000-000000000001"),
      summary = "여행 항공·선박 이벤트 저장",
      description =
          "도착 또는 출발 이벤트 한 건을 완전 교체하고 일정 stale 정책을 원자 적용합니다. 항공의 두 터미널 필드가 null이면 서버가 승인된 제주공항 ID를 확정하며, 불가하면 PLACE_NOT_FOUND를 반환합니다. 선박의 두 터미널 필드가 null이면 항구 미확정으로 저장하며 AI 생성은 지원하지 않습니다.")
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
  ResponseEntity<byte[]> put(
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
