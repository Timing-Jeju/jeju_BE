package com.timingjeju.api.domain.trip.controller.docs;

import com.timingjeju.api.domain.trip.dto.response.TripPlannerConditionsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface TripPlannerConditionsApiDocs {
  @Operation(
      operationId = "tripPlannerConditionsUpdate",
      summary = "날짜별 숙소와 여행 스타일 저장",
      description = "If-Match와 Idempotency-Key가 필수이며 canonical 장소 ID와 승인된 스타일 코드만 전체 교체합니다.")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = TripPlannerConditionsResponse.class)))
  ResponseEntity<byte[]> replace(
      String tripId, @Parameter(hidden = true) HttpServletRequest request);
}
