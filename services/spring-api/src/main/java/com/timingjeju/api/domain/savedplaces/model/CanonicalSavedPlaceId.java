package com.timingjeju.api.domain.savedplaces.model;

import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CanonicalSavedPlaceId {
  private static final Pattern CANONICAL =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private CanonicalSavedPlaceId() {}

  public static UUID parse(String value) {
    if (value == null || !CANONICAL.matcher(value).matches()) {
      throw SavedPlaceException.invalidRequest();
    }
    return UUID.fromString(value);
  }
}
