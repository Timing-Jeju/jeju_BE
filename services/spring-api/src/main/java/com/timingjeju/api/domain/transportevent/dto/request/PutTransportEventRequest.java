package com.timingjeju.api.domain.transportevent.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.TransportEventException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

@Schema(
    name = "TransportEventRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PutTransportEventRequest {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Pattern DATE_TIME_PATTERN =
      Pattern.compile(
          "^\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d+)?[+-]\\d{2}:\\d{2}$");

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"arrival", "departure"})
  private String eventType;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"flight", "ferry"})
  private String transportType;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, format = "uuid")
  private UUID terminalPlaceId;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      nullable = true,
      minLength = 1,
      maxLength = 100)
  private String customTerminalName;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      type = "string",
      format = "date-time",
      example = "2026-09-01T09:00:00+09:00")
  private OffsetDateTime scheduledAt;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      nullable = true,
      minLength = 1,
      maxLength = 30)
  private String transportNumber;

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      nullable = true,
      minLength = 1,
      maxLength = 500)
  private String note;

  private boolean eventTypePresent;
  private boolean transportTypePresent;
  private boolean terminalPlaceIdPresent;
  private boolean customTerminalNamePresent;
  private boolean scheduledAtPresent;
  private boolean transportNumberPresent;
  private boolean notePresent;

  @JsonSetter("eventType")
  public void setEventType(Object value) {
    eventTypePresent = true;
    eventType = requiredString(value);
  }

  @JsonSetter("transportType")
  public void setTransportType(Object value) {
    transportTypePresent = true;
    transportType = requiredString(value);
  }

  @JsonSetter("terminalPlaceId")
  public void setTerminalPlaceId(Object value) {
    terminalPlaceIdPresent = true;
    terminalPlaceId = nullableUuid(value);
  }

  @JsonSetter("customTerminalName")
  public void setCustomTerminalName(Object value) {
    customTerminalNamePresent = true;
    customTerminalName = nullableString(value);
  }

  @JsonSetter("scheduledAt")
  public void setScheduledAt(Object value) {
    scheduledAtPresent = true;
    scheduledAt = requiredOffsetDateTime(value);
  }

  @JsonSetter("transportNumber")
  public void setTransportNumber(Object value) {
    transportNumberPresent = true;
    transportNumber = nullableString(value);
  }

  @JsonSetter("note")
  public void setNote(Object value) {
    notePresent = true;
    note = nullableString(value);
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw TransportEventException.invalidRequest();
  }

  public PutTransportEventCommand toCommand() {
    if (!eventTypePresent
        || !transportTypePresent
        || !terminalPlaceIdPresent
        || !customTerminalNamePresent
        || !scheduledAtPresent
        || !transportNumberPresent
        || !notePresent) {
      throw TransportEventException.invalidRequest();
    }
    return new PutTransportEventCommand(
        eventType,
        transportType,
        terminalPlaceId,
        customTerminalName,
        scheduledAt,
        transportNumber,
        note);
  }

  private static String requiredString(Object value) {
    if (!(value instanceof String text)) throw TransportEventException.invalidRequest();
    return text;
  }

  private static String nullableString(Object value) {
    if (value == null) return null;
    return requiredString(value);
  }

  private static UUID nullableUuid(Object value) {
    if (value == null) return null;
    if (!(value instanceof String text) || !UUID_PATTERN.matcher(text).matches()) {
      throw TransportEventException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) throw TransportEventException.invalidRequest();
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  private static OffsetDateTime requiredOffsetDateTime(Object value) {
    if (!(value instanceof String text) || !DATE_TIME_PATTERN.matcher(text).matches()) {
      throw TransportEventException.invalidRequest();
    }
    try {
      return OffsetDateTime.parse(text);
    } catch (DateTimeParseException failure) {
      throw TransportEventException.invalidRequest();
    }
  }
}
