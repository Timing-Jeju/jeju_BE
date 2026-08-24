package com.timingjeju.api.application.legal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LegalDocumentSelection {

  private static final String FALLBACK_LOCALE = "ko-KR";

  private LegalDocumentSelection() {}

  public static List<LegalDocument> latest(List<LegalDocument> candidates, String locale) {
    Map<String, List<LegalDocument>> byType = new LinkedHashMap<>();
    candidates.forEach(
        document ->
            byType.computeIfAbsent(document.type(), ignored -> new ArrayList<>()).add(document));
    List<LegalDocument> selected = new ArrayList<>();
    byType.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              List<LegalDocument> localized =
                  entry.getValue().stream()
                      .filter(document -> locale.equals(document.locale()))
                      .toList();
              List<LegalDocument> eligible =
                  localized.isEmpty()
                      ? entry.getValue().stream()
                          .filter(document -> FALLBACK_LOCALE.equals(document.locale()))
                          .toList()
                      : localized;
              eligible.stream().max(documentOrder()).ifPresent(selected::add);
            });
    return List.copyOf(selected);
  }

  private static Comparator<LegalDocument> documentOrder() {
    return Comparator.comparing(LegalDocument::effectiveAt)
        .thenComparing(LegalDocument::version, LegalDocumentSelection::compareVersion)
        .thenComparing(LegalDocument::documentId, Comparator.reverseOrder());
  }

  static int compareVersion(String left, String right) {
    String[] leftParts = left.split("[\\.-]");
    String[] rightParts = right.split("[\\.-]");
    int length = Math.max(leftParts.length, rightParts.length);
    for (int index = 0; index < length; index++) {
      String leftPart = index < leftParts.length ? leftParts[index] : "0";
      String rightPart = index < rightParts.length ? rightParts[index] : "0";
      int compared = comparePart(leftPart, rightPart);
      if (compared != 0) {
        return compared;
      }
    }
    return 0;
  }

  private static int comparePart(String left, String right) {
    String normalizedLeft = left.startsWith("v") ? left.substring(1) : left;
    String normalizedRight = right.startsWith("v") ? right.substring(1) : right;
    try {
      return new java.math.BigInteger(normalizedLeft)
          .compareTo(new java.math.BigInteger(normalizedRight));
    } catch (NumberFormatException ignored) {
      return left.compareTo(right);
    }
  }
}
