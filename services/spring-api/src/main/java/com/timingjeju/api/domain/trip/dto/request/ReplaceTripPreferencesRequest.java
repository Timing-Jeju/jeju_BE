package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.ReplaceTripPreferencesCommand;
import com.timingjeju.api.application.trip.TripException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(
    name = "ReplaceTripPreferencesRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class ReplaceTripPreferencesRequest {
  private List<String> preferredCategories;
  private String arrivalRegionCode;
  private String departureRegionCode;
  private List<String> preferredRegionCodes;
  private UUID startPlaceId;
  private UUID endPlaceId;
  private List<PreferenceTransportModeRequest> transportModes;
  private boolean categoriesPresent;
  private boolean arrivalPresent;
  private boolean departurePresent;
  private boolean regionsPresent;
  private boolean startPresent;
  private boolean endPresent;
  private boolean modesPresent;

  @ArraySchema(
      minItems = 0,
      maxItems = 8,
      uniqueItems = true,
      arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
  public List<String> getPreferredCategories() {
    return preferredCategories;
  }

  @JsonSetter("preferredCategories")
  public void setPreferredCategories(List<String> value) {
    categoriesPresent = true;
    if (value == null) throw TripException.invalidRequest();
    preferredCategories = value;
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
  public String getArrivalRegionCode() {
    return arrivalRegionCode;
  }

  @JsonSetter("arrivalRegionCode")
  public void setArrivalRegionCode(Object value) {
    arrivalPresent = true;
    if (!(value instanceof String text)) throw TripException.invalidRequest();
    arrivalRegionCode = text;
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 50)
  public String getDepartureRegionCode() {
    return departureRegionCode;
  }

  @JsonSetter("departureRegionCode")
  public void setDepartureRegionCode(Object value) {
    departurePresent = true;
    if (!(value instanceof String text)) throw TripException.invalidRequest();
    departureRegionCode = text;
  }

  @ArraySchema(
      minItems = 0,
      maxItems = 20,
      uniqueItems = true,
      arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
  public List<String> getPreferredRegionCodes() {
    return preferredRegionCodes;
  }

  @JsonSetter("preferredRegionCodes")
  public void setPreferredRegionCodes(List<String> value) {
    regionsPresent = true;
    if (value == null) throw TripException.invalidRequest();
    preferredRegionCodes = value;
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
    startPresent = true;
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
    endPresent = true;
    endPlaceId = nullableUuid(value);
  }

  @ArraySchema(
      minItems = 1,
      maxItems = 3,
      arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
      schema = @Schema(implementation = PreferenceTransportModeRequest.class))
  public List<PreferenceTransportModeRequest> getTransportModes() {
    return transportModes;
  }

  @JsonSetter("transportModes")
  public void setTransportModes(List<PreferenceTransportModeRequest> value) {
    modesPresent = true;
    if (value == null) throw TripException.invalidRequest();
    transportModes = value;
  }

  @JsonAnySetter
  void rejectUnknown(String name, Object value) {
    throw TripException.invalidRequest();
  }

  public ReplaceTripPreferencesCommand toCommand() {
    if (!categoriesPresent
        || !arrivalPresent
        || !departurePresent
        || !regionsPresent
        || !startPresent
        || !endPresent
        || !modesPresent
        || preferredCategories.size() > 8
        || preferredRegionCodes.size() > 20
        || transportModes.isEmpty()
        || transportModes.size() > 3
        || preferredCategories.stream().anyMatch(ReplaceTripPreferencesRequest::invalidText)
        || preferredRegionCodes.stream().anyMatch(ReplaceTripPreferencesRequest::invalidRegion)
        || invalidRegion(arrivalRegionCode)
        || invalidRegion(departureRegionCode)
        || transportModes.stream().anyMatch(java.util.Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    return new ReplaceTripPreferencesCommand(
        preferredCategories,
        arrivalRegionCode,
        departureRegionCode,
        preferredRegionCodes,
        startPlaceId,
        endPlaceId,
        transportModes.stream().map(PreferenceTransportModeRequest::toModel).toList());
  }

  private static boolean invalidText(String value) {
    return value == null
        || value.codePointCount(0, value.length()) < 1
        || value.codePointCount(0, value.length()) > 50;
  }

  private static boolean invalidRegion(String value) {
    if (value == null || value.indexOf('\0') >= 0) return true;
    String trimmed = asciiTrim(value);
    int count = trimmed.codePointCount(0, trimmed.length());
    return count < 1 || count > 50;
  }

  private static UUID nullableUuid(Object value) {
    if (value == null) return null;
    if (!(value instanceof String text)) throw TripException.invalidRequest();
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) throw TripException.invalidRequest();
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw TripException.invalidRequest();
    }
  }

  private static String asciiTrim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isAsciiTrimCharacter(value.charAt(start))) start++;
    while (end > start && isAsciiTrimCharacter(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  private static boolean isAsciiTrimCharacter(char value) {
    return switch (value) {
      case ' ', '\t', '\n', '\r', '\f', '\u000B' -> true;
      default -> false;
    };
  }
}
