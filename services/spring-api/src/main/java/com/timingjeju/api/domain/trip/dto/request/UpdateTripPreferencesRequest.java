package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.UpdateTripPreferencesCommand;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Schema(name = "PreferencesRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class UpdateTripPreferencesRequest {
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private List<String> preferredCategories;
  private String arrivalRegionCode;
  private String departureRegionCode;
  private List<String> preferredRegionCodes;
  private UUID startPlaceId;
  private UUID endPlaceId;
  private List<TripTransportModeRequest> transportModes;
  private boolean preferredCategoriesPresent;
  private boolean arrivalRegionCodePresent;
  private boolean departureRegionCodePresent;
  private boolean preferredRegionCodesPresent;
  private boolean startPlaceIdPresent;
  private boolean endPlaceIdPresent;
  private boolean transportModesPresent;

  @ArraySchema(
      minItems = 0,
      maxItems = 8,
      arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
      schema =
          @Schema(
              type = "string",
              allowableValues = {
                "tourist_attraction",
                "cultural_facility",
                "festival",
                "travel_course",
                "leisure",
                "restaurant",
                "cafe",
                "shopping"
              }))
  public List<String> getPreferredCategories() {
    return preferredCategories;
  }

  @JsonSetter("preferredCategories")
  public void setPreferredCategories(Object value) {
    preferredCategoriesPresent = true;
    preferredCategories = stringList(value);
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
  public String getArrivalRegionCode() {
    return arrivalRegionCode;
  }

  @JsonSetter("arrivalRegionCode")
  public void setArrivalRegionCode(Object value) {
    arrivalRegionCodePresent = true;
    arrivalRegionCode = string(value);
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
  public String getDepartureRegionCode() {
    return departureRegionCode;
  }

  @JsonSetter("departureRegionCode")
  public void setDepartureRegionCode(Object value) {
    departureRegionCodePresent = true;
    departureRegionCode = string(value);
  }

  @ArraySchema(
      minItems = 0,
      maxItems = 20,
      arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
      schema = @Schema(type = "string", minLength = 1, maxLength = 50))
  public List<String> getPreferredRegionCodes() {
    return preferredRegionCodes;
  }

  @JsonSetter("preferredRegionCodes")
  public void setPreferredRegionCodes(Object value) {
    preferredRegionCodesPresent = true;
    preferredRegionCodes = stringList(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      nullable = true,
      types = {"string", "null"},
      format = "uuid")
  public UUID getStartPlaceId() {
    return startPlaceId;
  }

  @JsonSetter("startPlaceId")
  public void setStartPlaceId(Object value) {
    startPlaceIdPresent = true;
    startPlaceId = nullableUuid(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      nullable = true,
      types = {"string", "null"},
      format = "uuid")
  public UUID getEndPlaceId() {
    return endPlaceId;
  }

  @JsonSetter("endPlaceId")
  public void setEndPlaceId(Object value) {
    endPlaceIdPresent = true;
    endPlaceId = nullableUuid(value);
  }

  @ArraySchema(
      minItems = 1,
      maxItems = 3,
      arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
      schema = @Schema(implementation = TripTransportModeRequest.class))
  public List<TripTransportModeRequest> getTransportModes() {
    return transportModes;
  }

  @JsonSetter("transportModes")
  public void setTransportModes(List<TripTransportModeRequest> value) {
    transportModesPresent = true;
    if (value == null || value.stream().anyMatch(java.util.Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    transportModes = List.copyOf(value);
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TripException.invalidRequest();
  }

  public UpdateTripPreferencesCommand toCommand() {
    if (!preferredCategoriesPresent
        || !arrivalRegionCodePresent
        || !departureRegionCodePresent
        || !preferredRegionCodesPresent
        || !startPlaceIdPresent
        || !endPlaceIdPresent
        || !transportModesPresent) {
      throw TripException.invalidRequest();
    }
    return new UpdateTripPreferencesCommand(
        preferredCategories,
        arrivalRegionCode,
        departureRegionCode,
        preferredRegionCodes,
        startPlaceId,
        endPlaceId,
        transportModes.stream().map(TripTransportModeRequest::toModel).toList());
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> raw)) {
      throw TripException.invalidRequest();
    }
    List<String> result = new ArrayList<>(raw.size());
    for (Object item : raw) {
      result.add(string(item));
    }
    return List.copyOf(result);
  }

  private static String string(Object value) {
    if (!(value instanceof String text)) {
      throw TripException.invalidRequest();
    }
    return text;
  }

  private static UUID nullableUuid(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String raw) || !CANONICAL_UUID.matcher(raw).matches()) {
      throw TripException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) {
        throw TripException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw TripException.invalidRequest();
    }
  }
}
