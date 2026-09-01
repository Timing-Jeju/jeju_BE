package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripTransportMode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TransportMode", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class TripTransportModeRequest {
  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"public_transit", "rental_car", "taxi"})
  private String mode;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "3")
  private Integer priority;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  private Boolean primary;

  private boolean modePresent;
  private boolean priorityPresent;
  private boolean primaryPresent;

  public TripTransportModeRequest() {}

  TripTransportModeRequest(String mode, Integer priority, Boolean primary) {
    this.mode = mode;
    this.priority = priority;
    this.primary = primary;
    this.modePresent = true;
    this.priorityPresent = true;
    this.primaryPresent = true;
  }

  public String getMode() {
    return mode;
  }

  public Integer getPriority() {
    return priority;
  }

  public Boolean getPrimary() {
    return primary;
  }

  @JsonSetter("mode")
  public void setMode(Object mode) {
    modePresent = true;
    if (!(mode instanceof String value)) {
      throw TripException.invalidRequest();
    }
    this.mode = value;
  }

  @JsonSetter("priority")
  public void setPriority(Object priority) {
    priorityPresent = true;
    if (!(priority instanceof Integer value)) {
      throw TripException.invalidRequest();
    }
    this.priority = value;
  }

  @JsonSetter("primary")
  public void setPrimary(Object primary) {
    primaryPresent = true;
    if (!(primary instanceof Boolean value)) {
      throw TripException.invalidRequest();
    }
    this.primary = value;
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TripException.invalidRequest();
  }

  public TripTransportMode toModel() {
    if (!modePresent || !priorityPresent || !primaryPresent) {
      throw TripException.invalidRequest();
    }
    return new TripTransportMode(mode, priority, primary);
  }
}
