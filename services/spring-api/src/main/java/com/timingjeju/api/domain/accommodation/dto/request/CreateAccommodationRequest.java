package com.timingjeju.api.domain.accommodation.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

@Schema(
    name = "CreateAccommodationRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class CreateAccommodationRequest {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern TIME_PATTERN = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$");

  private UUID placeId;

  private String customName;

  private LocalDate checkInDate;

  private LocalDate checkOutDate;

  private LocalTime checkInTime;

  private LocalTime checkOutTime;

  private boolean placeIdPresent;
  private boolean customNamePresent;
  private boolean checkInDatePresent;
  private boolean checkOutDatePresent;
  private boolean checkInTimePresent;
  private boolean checkOutTimePresent;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      types = {"string", "null"},
      format = "uuid")
  @JsonSetter("placeId")
  public void setPlaceId(Object value) {
    placeIdPresent = true;
    placeId = nullableUuid(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      types = {"string", "null"},
      minLength = 1,
      maxLength = 100)
  @JsonSetter("customName")
  public void setCustomName(Object value) {
    customNamePresent = true;
    customName = nullableString(value);
  }

  @Schema(
      type = "string",
      requiredMode = Schema.RequiredMode.REQUIRED,
      format = "date",
      example = "2026-09-01")
  @JsonSetter("checkInDate")
  public void setCheckInDate(Object value) {
    checkInDatePresent = true;
    checkInDate = requiredDate(value);
  }

  @Schema(
      type = "string",
      requiredMode = Schema.RequiredMode.REQUIRED,
      format = "date",
      example = "2026-09-02")
  @JsonSetter("checkOutDate")
  public void setCheckOutDate(Object value) {
    checkOutDatePresent = true;
    checkOutDate = requiredDate(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      type = "string",
      pattern = "^(?:[01]\\d|2[0-3]):[0-5]\\d$",
      example = "15:00")
  @JsonSetter("checkInTime")
  public void setCheckInTime(Object value) {
    checkInTimePresent = true;
    checkInTime = requiredTime(value);
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      type = "string",
      pattern = "^(?:[01]\\d|2[0-3]):[0-5]\\d$",
      example = "11:00")
  @JsonSetter("checkOutTime")
  public void setCheckOutTime(Object value) {
    checkOutTimePresent = true;
    checkOutTime = requiredTime(value);
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw AccommodationException.invalidRequest();
  }

  public CreateAccommodationCommand toCommand() {
    if (!placeIdPresent
        || !customNamePresent
        || !checkInDatePresent
        || !checkOutDatePresent
        || !checkInTimePresent
        || !checkOutTimePresent) {
      throw AccommodationException.invalidRequest();
    }
    return new CreateAccommodationCommand(
        placeId, customName, checkInDate, checkOutDate, checkInTime, checkOutTime);
  }

  static UUID nullableUuid(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text) || !UUID_PATTERN.matcher(text).matches()) {
      throw AccommodationException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw AccommodationException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  static String nullableString(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw AccommodationException.invalidRequest();
    }
    return text;
  }

  static LocalDate requiredDate(Object value) {
    if (!(value instanceof String text) || !DATE_PATTERN.matcher(text).matches()) {
      throw AccommodationException.invalidRequest();
    }
    try {
      LocalDate parsed = LocalDate.parse(text);
      if (!parsed.toString().equals(text)) {
        throw AccommodationException.invalidRequest();
      }
      return parsed;
    } catch (DateTimeParseException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  static LocalTime requiredTime(Object value) {
    if (!(value instanceof String text) || !TIME_PATTERN.matcher(text).matches()) {
      throw AccommodationException.invalidRequest();
    }
    try {
      return LocalTime.parse(text);
    } catch (DateTimeParseException failure) {
      throw AccommodationException.invalidRequest();
    }
  }
}
