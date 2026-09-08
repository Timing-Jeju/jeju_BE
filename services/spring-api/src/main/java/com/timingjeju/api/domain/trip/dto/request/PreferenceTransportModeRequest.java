package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripTransportMode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    name = "PreferenceTransportMode",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PreferenceTransportModeRequest {
  private String mode;
  private Integer priority;
  private Boolean primary;
  private boolean modePresent;
  private boolean priorityPresent;
  private boolean primaryPresent;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"public_transit", "rental_car", "taxi"})
  public String getMode() {
    return mode;
  }

  @JsonSetter("mode")
  public void setMode(Object value) {
    modePresent = true;
    if (!(value instanceof String text)) throw TripException.invalidRequest();
    mode = text;
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "3")
  public Integer getPriority() {
    return priority;
  }

  @JsonSetter("priority")
  public void setPriority(Object value) {
    priorityPresent = true;
    if (!(value instanceof Integer number)) throw TripException.invalidRequest();
    priority = number;
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  public Boolean getPrimary() {
    return primary;
  }

  @JsonSetter("primary")
  public void setPrimary(Object value) {
    primaryPresent = true;
    if (!(value instanceof Boolean flag)) throw TripException.invalidRequest();
    primary = flag;
  }

  @JsonAnySetter
  void rejectUnknown(String name, Object value) {
    throw TripException.invalidRequest();
  }

  TripTransportMode toModel() {
    if (!modePresent || !priorityPresent || !primaryPresent) {
      throw TripException.invalidRequest();
    }
    return new TripTransportMode(mode, priority, primary);
  }
}
