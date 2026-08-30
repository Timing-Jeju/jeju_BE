package com.timingjeju.api.domain.savedplaces.model;

import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;

public record SavedPlaceCommand(
    UUID placeId, String memo, List<String> tags, int priority, Integer targetDay) {
  public static SavedPlaceCommand create(
      UUID placeId, String memo, List<String> tags, Integer priority, Integer targetDay) {
    String normalizedMemo = normalizeMemo(memo);
    if (normalizedMemo != null
        && (normalizedMemo.isEmpty()
            || normalizedMemo.codePointCount(0, normalizedMemo.length()) > 2000
            || control(normalizedMemo)))
      throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    List<String> normalizedTags = normalizeTags(tags);
    if (normalizedTags.size() > 20
        || normalizedTags.stream().distinct().count() != normalizedTags.size())
      throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    return new SavedPlaceCommand(
        placeId,
        normalizedMemo,
        normalizedTags,
        normalizePriority(priority),
        normalizeTargetDay(targetDay));
  }

  public static String normalizeMemo(String memo) {
    String normalizedMemo =
        memo == null ? null : Normalizer.normalize(trimAsciiSpace(memo), Normalizer.Form.NFC);
    if (normalizedMemo != null
        && (normalizedMemo.isEmpty()
            || normalizedMemo.codePointCount(0, normalizedMemo.length()) > 2000
            || control(normalizedMemo)))
      throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    return normalizedMemo;
  }

  public static List<String> normalizeTags(List<String> tags) {
    List<String> normalizedTags =
        tags == null
            ? List.of()
            : tags.stream()
                .map(SavedPlaceCommand::tag)
                .sorted(SavedPlaceCommand::compareCodePoints)
                .toList();
    if (normalizedTags.size() > 20
        || normalizedTags.stream().distinct().count() != normalizedTags.size())
      throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    return normalizedTags;
  }

  public static int normalizePriority(Integer priority) {
    int value = priority == null ? 0 : priority;
    if (value < 0 || value > 5) throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    return value;
  }

  public static Integer normalizeTargetDay(Integer targetDay) {
    if (targetDay != null && (targetDay < 1 || targetDay > 365))
      throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    return targetDay;
  }

  private static String tag(String value) {
    if (value == null) throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    String normalized = Normalizer.normalize(trimAsciiSpace(value), Normalizer.Form.NFC);
    if (normalized.isEmpty()
        || normalized.codePointCount(0, normalized.length()) > 50
        || control(normalized)) throw SavedPlaceException.of("SAVED_PLACE_CONSTRAINT_VIOLATION");
    return normalized;
  }

  private static int compareCodePoints(String left, String right) {
    var leftPoints = left.codePoints().iterator();
    var rightPoints = right.codePoints().iterator();
    while (leftPoints.hasNext() && rightPoints.hasNext()) {
      int compared = Integer.compare(leftPoints.nextInt(), rightPoints.nextInt());
      if (compared != 0) return compared;
    }
    return Boolean.compare(leftPoints.hasNext(), rightPoints.hasNext());
  }

  private static boolean control(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  static String trimAsciiSpace(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == ' ') start++;
    while (end > start && value.charAt(end - 1) == ' ') end--;
    return value.substring(start, end);
  }
}
