package com.timingjeju.api.application.trip;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TripEntityTag {
  private static final Pattern STRONG_TAG =
      Pattern.compile(
          "^\\\"trip-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-r([1-9][0-9]*)\\\"$");

  private TripEntityTag() {}

  public static String strong(UUID tripId, long revision) {
    if (tripId == null || revision < 1) {
      throw new IllegalArgumentException("tripId와 양수 revision이 필요합니다.");
    }
    return "\"trip-" + tripId + "-r" + revision + "\"";
  }

  public static TripExpectedRevision parse(String raw) {
    if (raw == null) {
      throw TripException.ifMatchRequired();
    }
    Matcher matcher = STRONG_TAG.matcher(raw);
    if (!matcher.matches()) {
      throw TripException.invalidIfMatch();
    }
    try {
      return new TripExpectedRevision(
          UUID.fromString(matcher.group(1)), Long.parseLong(matcher.group(2)));
    } catch (IllegalArgumentException failure) {
      throw TripException.invalidIfMatch();
    }
  }
}
