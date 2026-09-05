package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.UpdateTripPlacePreferencesCommand;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
    name = "PlacePreferencesRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class UpdateTripPlacePreferencesRequest {
  private List<TripPlacePreferenceRequest> items;
  private boolean itemsPresent;

  @ArraySchema(
      minItems = 0,
      maxItems = 100,
      arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
      schema = @Schema(implementation = TripPlacePreferenceRequest.class))
  public List<TripPlacePreferenceRequest> getItems() {
    return items;
  }

  @JsonSetter("items")
  public void setItems(List<TripPlacePreferenceRequest> value) {
    itemsPresent = true;
    if (value == null || value.stream().anyMatch(java.util.Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    items = List.copyOf(value);
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TripException.invalidRequest();
  }

  public UpdateTripPlacePreferencesCommand toCommand() {
    if (!itemsPresent) {
      throw TripException.invalidRequest();
    }
    return new UpdateTripPlacePreferencesCommand(
        items.stream().map(TripPlacePreferenceRequest::toModel).toList());
  }
}
