package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripTransportMode;

public final class TripTransportModeRequest {
  private String mode;
  private Integer priority;
  private Boolean primary;

  public TripTransportModeRequest() {}

  TripTransportModeRequest(String mode, Integer priority, Boolean primary) {
    this.mode = mode;
    this.priority = priority;
    this.primary = primary;
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
    if (!(mode instanceof String value)) {
      throw TripException.invalidRequest();
    }
    this.mode = value;
  }

  @JsonSetter("priority")
  public void setPriority(Object priority) {
    if (!(priority instanceof Integer value)) {
      throw TripException.invalidRequest();
    }
    this.priority = value;
  }

  @JsonSetter("primary")
  public void setPrimary(Object primary) {
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
    return new TripTransportMode(
        mode, priority == null ? 0 : priority, Boolean.TRUE.equals(primary));
  }
}
