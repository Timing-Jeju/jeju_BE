package com.timingjeju.api.domain.trip.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlacePreference;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import java.util.regex.Pattern;

@Schema(name = "PlacePreferenceItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class TripPlacePreferenceRequest {
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private UUID placeId;
  private String type;
  private Integer targetDayNo;
  private Integer priority;
  private boolean placeIdPresent;
  private boolean typePresent;
  private boolean targetDayNoPresent;
  private boolean priorityPresent;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
  public UUID getPlaceId() {
    return placeId;
  }

  @JsonSetter("placeId")
  public void setPlaceId(Object value) {
    placeIdPresent = true;
    placeId = canonicalUuid(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"must_visit", "avoid"})
  public String getType() {
    return type;
  }

  @JsonSetter("type")
  public void setType(Object value) {
    typePresent = true;
    type = string(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      nullable = true,
      types = {"integer", "null"},
      minimum = "1",
      maximum = "30")
  public Integer getTargetDayNo() {
    return targetDayNo;
  }

  @JsonSetter("targetDayNo")
  public void setTargetDayNo(Object value) {
    targetDayNoPresent = true;
    targetDayNo = nullableInteger(value);
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "100")
  public Integer getPriority() {
    return priority;
  }

  @JsonSetter("priority")
  public void setPriority(Object value) {
    priorityPresent = true;
    priority = integer(value);
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TripException.invalidRequest();
  }

  TripPlacePreference toModel() {
    if (!placeIdPresent || !typePresent || !targetDayNoPresent || !priorityPresent) {
      throw TripException.invalidRequest();
    }
    return new TripPlacePreference(placeId, type, targetDayNo, priority);
  }

  private static UUID canonicalUuid(Object value) {
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

  private static String string(Object value) {
    if (!(value instanceof String text)) {
      throw TripException.invalidRequest();
    }
    return text;
  }

  private static Integer integer(Object value) {
    if (!(value instanceof Integer number)) {
      throw TripException.invalidRequest();
    }
    return number;
  }

  private static Integer nullableInteger(Object value) {
    return value == null ? null : integer(value);
  }
}
