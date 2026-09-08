package com.timingjeju.api.application.accommodation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

public record PatchAccommodationCommand(
    AccommodationPatchValue<UUID> placeId,
    AccommodationPatchValue<String> customName,
    AccommodationPatchValue<LocalDate> checkInDate,
    AccommodationPatchValue<LocalDate> checkOutDate,
    AccommodationPatchValue<LocalTime> checkInTime,
    AccommodationPatchValue<LocalTime> checkOutTime) {
  public PatchAccommodationCommand {
    Objects.requireNonNull(placeId);
    Objects.requireNonNull(customName);
    Objects.requireNonNull(checkInDate);
    Objects.requireNonNull(checkOutDate);
    Objects.requireNonNull(checkInTime);
    Objects.requireNonNull(checkOutTime);
  }

  public static PatchAccommodationCommand empty() {
    return new PatchAccommodationCommand(
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted());
  }

  public static PatchAccommodationCommand identity(
      AccommodationPatchValue<UUID> placeId, AccommodationPatchValue<String> customName) {
    return new PatchAccommodationCommand(
        placeId,
        customName,
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted());
  }

  public static PatchAccommodationCommand withCheckInTime(
      AccommodationPatchValue<LocalTime> checkInTime) {
    return new PatchAccommodationCommand(
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        AccommodationPatchValue.omitted(),
        checkInTime,
        AccommodationPatchValue.omitted());
  }

  public boolean emptyPatch() {
    return !placeId.present()
        && !customName.present()
        && !checkInDate.present()
        && !checkOutDate.present()
        && !checkInTime.present()
        && !checkOutTime.present();
  }
}
