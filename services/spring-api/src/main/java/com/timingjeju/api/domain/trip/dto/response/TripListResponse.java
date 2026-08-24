package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripPage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "TripsListResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TripListResponse(
    @ArraySchema(
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
            schema = @Schema(implementation = TripSummaryResponse.class))
        List<TripSummaryResponse> items,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Page page) {
  @Schema(name = "CursorPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Page(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "50") int size,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              types = {"string", "null"},
              minLength = 1,
              maxLength = 2048)
          String nextCursor) {}

  public static TripListResponse from(TripPage source) {
    return new TripListResponse(
        source.items().stream().map(TripSummaryResponse::from).toList(),
        new Page(source.size(), source.hasNext(), source.nextCursor()));
  }
}
