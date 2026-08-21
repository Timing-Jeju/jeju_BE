package com.timingjeju.api.domain.places.model;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CanonicalPlaceCategory {

  public static final String OPEN_API_PATTERN = "^(?:[A-Z]{2}|content-type:[0-9]{1,10})$";

  private static final Pattern CANONICAL = Pattern.compile("(?:[A-Z]{2}|content-type:[0-9]{1,10})");
  private static final Pattern CONTENT_TYPE = Pattern.compile("[0-9]{1,10}");

  private CanonicalPlaceCategory() {}

  public static boolean isValid(String value) {
    return value != null && CANONICAL.matcher(value).matches();
  }

  public static String fromSource(String lclsSystm1, String contentTypeId) {
    if (lclsSystm1 != null && !lclsSystm1.isBlank()) {
      String category = lclsSystm1.strip().toUpperCase(Locale.ROOT);
      if (CANONICAL.matcher(category).matches() && !category.startsWith("content-type:")) {
        return category;
      }
      throw new IllegalArgumentException("Invalid TourAPI classification category");
    }
    String contentType = contentTypeId == null ? "" : contentTypeId.strip();
    if (!CONTENT_TYPE.matcher(contentType).matches()) {
      throw new IllegalArgumentException("Invalid TourAPI content type category fallback");
    }
    return "content-type:" + contentType;
  }
}
