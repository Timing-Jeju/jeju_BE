package com.timingjeju.api.domain.trip.controller.docs;

import com.timingjeju.api.domain.trip.dto.response.PlannerConditionsMutationResponse;
import com.timingjeju.api.domain.trip.dto.response.TripPlannerConditionsResponse;
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

public interface TripPlannerConditionsApiDocs {
  @Operation(
      operationId = "tripPlannerConditionsUpdate",
      summary = "날짜별 숙소와 여행 스타일 저장",
      description = "If-Match와 Idempotency-Key가 필수이며 canonical 장소 ID와 승인된 스타일 코드만 전체 교체합니다.")
  @RequestBody(
      required = true,
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = TripPlannerConditionsResponse.class)))
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
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "조건 저장 완료",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PlannerConditionsMutationResponse.class)),
        headers = {
          @Header(name = "ETag", schema = @Schema(type = "string")),
          @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean"))
        }),
    @ApiResponse(responseCode = "400", description = "형식 또는 필수 헤더 오류"),
    @ApiResponse(responseCode = "401", description = "인증 필요"),
    @ApiResponse(responseCode = "404", description = "소유한 여행 없음"),
    @ApiResponse(responseCode = "409", description = "여행 revision 또는 멱등성 충돌"),
    @ApiResponse(responseCode = "422", description = "Day 또는 장소 조건 위반"),
    @ApiResponse(responseCode = "503", description = "저장소 사용 불가")
  })
  ResponseEntity<byte[]> replace(
      String tripId, @Parameter(hidden = true) HttpServletRequest request);
}
