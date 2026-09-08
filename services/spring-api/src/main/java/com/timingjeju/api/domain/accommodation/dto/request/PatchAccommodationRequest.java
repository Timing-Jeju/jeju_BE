package com.timingjeju.api.domain.accommodation.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationPatchValue;
import com.timingjeju.api.application.accommodation.PatchAccommodationCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Schema(
    name = "PatchAccommodationRequest",
    minProperties = 1,
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PatchAccommodationRequest {
  @Schema(nullable = true, format = "uuid")
  private AccommodationPatchValue<UUID> placeId = AccommodationPatchValue.omitted();

  @Schema(nullable = true, minLength = 1, maxLength = 100)
  private AccommodationPatchValue<String> customName = AccommodationPatchValue.omitted();

  @Schema(format = "date", example = "2026-09-01")
  private AccommodationPatchValue<LocalDate> checkInDate = AccommodationPatchValue.omitted();

  @Schema(format = "date", example = "2026-09-02")
  private AccommodationPatchValue<LocalDate> checkOutDate = AccommodationPatchValue.omitted();

  @Schema(type = "string", pattern = "^(?:[01]\\d|2[0-3]):[0-5]\\d$", example = "15:00")
  private AccommodationPatchValue<LocalTime> checkInTime = AccommodationPatchValue.omitted();

  @Schema(type = "string", pattern = "^(?:[01]\\d|2[0-3]):[0-5]\\d$", example = "11:00")
  private AccommodationPatchValue<LocalTime> checkOutTime = AccommodationPatchValue.omitted();

  @JsonSetter("placeId")
  public void setPlaceId(Object value) {
    placeId = AccommodationPatchValue.present(CreateAccommodationRequest.nullableUuid(value));
  }

  @JsonSetter("customName")
  public void setCustomName(Object value) {
    customName = AccommodationPatchValue.present(CreateAccommodationRequest.nullableString(value));
  }

  @JsonSetter("checkInDate")
  public void setCheckInDate(Object value) {
    checkInDate = AccommodationPatchValue.present(CreateAccommodationRequest.requiredDate(value));
  }

  @JsonSetter("checkOutDate")
  public void setCheckOutDate(Object value) {
    checkOutDate = AccommodationPatchValue.present(CreateAccommodationRequest.requiredDate(value));
  }

  @JsonSetter("checkInTime")
  public void setCheckInTime(Object value) {
    checkInTime = AccommodationPatchValue.present(CreateAccommodationRequest.requiredTime(value));
  }

  @JsonSetter("checkOutTime")
  public void setCheckOutTime(Object value) {
    checkOutTime = AccommodationPatchValue.present(CreateAccommodationRequest.requiredTime(value));
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw AccommodationException.invalidRequest();
  }

  public PatchAccommodationCommand toCommand() {
    PatchAccommodationCommand command =
        new PatchAccommodationCommand(
            placeId, customName, checkInDate, checkOutDate, checkInTime, checkOutTime);
    if (command.emptyPatch()) {
      throw AccommodationException.invalidRequest();
    }
    return command;
  }
}
