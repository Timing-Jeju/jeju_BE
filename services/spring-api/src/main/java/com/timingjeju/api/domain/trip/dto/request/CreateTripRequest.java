package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.CreateTripCommand;
import com.timingjeju.api.application.trip.TripException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "CreateTripRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class CreateTripRequest {
  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
  private String title;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date")
  private LocalDate startDate;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date")
  private LocalDate endDate;

  @Schema(defaultValue = "Asia/Seoul", allowableValues = "Asia/Seoul")
  private String timezone;

  private boolean timezonePresent;

  @Schema(
      defaultValue = "normal",
      allowableValues = {"slow", "normal", "fast"})
  private String userPace;

  private boolean userPacePresent;

  @ArraySchema(
      minItems = 1,
      maxItems = 3,
      arraySchema =
          @Schema(defaultValue = "[{\"mode\":\"public_transit\",\"priority\":1,\"primary\":true}]"),
      schema = @Schema(implementation = TripTransportModeRequest.class))
  private List<TripTransportModeRequest> transportModes;

  private boolean transportModesPresent;

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

  @Schema(defaultValue = "[{\"mode\":\"public_transit\",\"priority\":1,\"primary\":true}]")
  public List<TripTransportModeRequest> getTransportModes() {
    return transportModes;
  }

  @JsonSetter("transportModes")
  public void setTransportModes(List<TripTransportModeRequest> transportModes) {
    this.transportModesPresent = true;
    if (transportModes == null) {
      throw TripException.invalidRequest();
    }
    this.transportModes = transportModes;
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TripException.invalidRequest();
  }

  public CreateTripCommand toCommand() {
    List<TripTransportModeRequest> requestedModes =
        !transportModesPresent
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
