package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.CreateTripCommand;
import com.timingjeju.api.application.trip.TripException;
import java.time.LocalDate;
import java.util.List;

public final class CreateTripRequest {
  private String title;
  private LocalDate startDate;
  private LocalDate endDate;
  private String timezone;
  private boolean timezonePresent;
  private String userPace;
  private boolean userPacePresent;
  private List<TripTransportModeRequest> transportModes;

  public String getTitle() {
    return title;
  }

  @JsonSetter("title")
  public void setTitle(Object title) {
    if (!(title instanceof String value)) {
      throw TripException.invalidRequest();
    }
    this.title = value;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  public String getTimezone() {
    return timezone;
  }

  @JsonSetter("timezone")
  public void setTimezone(Object timezone) {
    if (!(timezone instanceof String value)) {
      throw TripException.invalidRequest();
    }
    this.timezone = value;
    this.timezonePresent = true;
  }

  public String getUserPace() {
    return userPace;
  }

  @JsonSetter("userPace")
  public void setUserPace(Object userPace) {
    if (!(userPace instanceof String value)) {
      throw TripException.invalidRequest();
    }
    this.userPace = value;
    this.userPacePresent = true;
  }

  public List<TripTransportModeRequest> getTransportModes() {
    return transportModes;
  }

  public void setTransportModes(List<TripTransportModeRequest> transportModes) {
    this.transportModes = transportModes;
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TripException.invalidRequest();
  }

  public CreateTripCommand toCommand() {
    List<TripTransportModeRequest> requestedModes =
        transportModes == null
            ? List.of(new TripTransportModeRequest("public_transit", 1, true))
            : transportModes;
    if (requestedModes.stream().anyMatch(java.util.Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    return new CreateTripCommand(
        title,
        startDate,
        endDate,
        timezonePresent ? timezone : "Asia/Seoul",
        userPacePresent ? userPace : "normal",
        requestedModes.stream().map(TripTransportModeRequest::toModel).toList());
  }
}
