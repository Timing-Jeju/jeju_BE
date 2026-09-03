package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.PatchTripCommand;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPatchValue;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Schema(
    name = "PatchTripRequest",
    minProperties = 1,
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PatchTripRequest {
  @Schema(minLength = 1, maxLength = 100)
  private TripPatchValue<String> title = TripPatchValue.omitted();

  @Schema(format = "date")
  private TripPatchValue<LocalDate> startDate = TripPatchValue.omitted();

  @Schema(format = "date")
  private TripPatchValue<LocalDate> endDate = TripPatchValue.omitted();

  @Schema(allowableValues = "Asia/Seoul")
  private TripPatchValue<String> timezone = TripPatchValue.omitted();

  @Schema(allowableValues = {"slow", "normal", "fast"})
  private TripPatchValue<String> userPace = TripPatchValue.omitted();

  @ArraySchema(
      minItems = 1,
      maxItems = 3,
      schema = @Schema(implementation = TripTransportModeRequest.class))
  private TripPatchValue<List<TripTransportModeRequest>> transportModes = TripPatchValue.omitted();

  @JsonSetter("title")
  public void setTitle(Object value) {
    title = TripPatchValue.present(requiredString(value));
  }

  @JsonSetter("startDate")
  public void setStartDate(Object value) {
    startDate = TripPatchValue.present(requiredDate(value));
  }

  @JsonSetter("endDate")
  public void setEndDate(Object value) {
    endDate = TripPatchValue.present(requiredDate(value));
  }

  @JsonSetter("timezone")
  public void setTimezone(Object value) {
    timezone = TripPatchValue.present(requiredString(value));
  }

  @JsonSetter("userPace")
  public void setUserPace(Object value) {
    userPace = TripPatchValue.present(requiredString(value));
  }

  @JsonSetter("transportModes")
  public void setTransportModes(List<TripTransportModeRequest> value) {
    if (value == null || value.stream().anyMatch(java.util.Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    transportModes = TripPatchValue.present(List.copyOf(value));
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TripException.invalidRequest();
  }

  public PatchTripCommand toCommand() {
    TripPatchValue<List<com.timingjeju.api.application.trip.TripTransportMode>> modeCommand =
        transportModes.present()
            ? TripPatchValue.present(
                transportModes.value().stream().map(TripTransportModeRequest::toModel).toList())
            : TripPatchValue.omitted();
    PatchTripCommand command =
        new PatchTripCommand(title, startDate, endDate, timezone, userPace, modeCommand);
    if (command.emptyPatch()) {
      throw TripException.invalidRequest();
    }
    return command;
  }

  private static String requiredString(Object value) {
    if (!(value instanceof String text)) {
      throw TripException.invalidRequest();
    }
    return text;
  }

  private static LocalDate requiredDate(Object value) {
    if (!(value instanceof String text)) {
      throw TripException.invalidRequest();
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException failure) {
      throw TripException.invalidRequest();
    }
  }
}
