package com.timingjeju.api.application.commandinput;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class CommandInputCanonicalizer {
  private static final Duration LOCATION_TTL = Duration.ofHours(24);
  private static final Pattern CANONICAL_RFC3339 =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,9}))?(Z|([+-])([0-9]{2}):([0-9]{2}))");
  private final ObjectMapper objectMapper;

  public CommandInputCanonicalizer(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
  }

  public CommandInputSnapshot canonicalize(CommandInputRequest request) {
    Objects.requireNonNull(request, "request는 필수입니다.");
    validateParentType(request.parent(), request.runType());
    if (request.schemaVersion() != 1) {
      throw new IllegalArgumentException("지원하지 않는 schema version입니다.");
    }
    if (!request.structuredInput().isObject()) {
      throw new IllegalArgumentException("structured input은 JSON object여야 합니다.");
    }
    validateClosedProjection(request.runType(), request.structuredInput());

    String structuredInput = canonicalJson(request.structuredInput());
    CommandLocationSnapshot location = canonicalLocation(request.location());
    String locationDigest = location == null ? null : sha256(location.canonicalCoarseLocation());

    ObjectNode hashDocument = objectMapper.createObjectNode();
    hashDocument.put("algorithmVersion", request.algorithmVersion());
    if (request.baseScheduleVersionId() == null) {
      hashDocument.putNull("baseScheduleVersionId");
    } else {
      hashDocument.put("baseScheduleVersionId", request.baseScheduleVersionId().toString());
    }
    hashDocument.put("contractVersion", request.contractVersion());
    if (locationDigest == null) {
      hashDocument.putNull("locationDigest");
    } else {
      hashDocument.put("locationDigest", locationDigest);
    }
    hashDocument.put("locationSupplied", location != null);
    hashDocument.put("runType", request.runType());
    hashDocument.put("schemaVersion", request.schemaVersion());
    try {
      hashDocument.set("structuredInput", objectMapper.readTree(structuredInput));
    } catch (RuntimeException impossible) {
      throw new IllegalStateException("canonical command input 구성에 실패했습니다.");
    }

    return new CommandInputSnapshot(
        request.parent(),
        request.runType(),
        request.schemaVersion(),
        request.contractVersion(),
        request.algorithmVersion(),
        structuredInput,
        sha256(canonicalJson(hashDocument)),
        request.ownerUserId(),
        request.tripPlanId(),
        request.baseScheduleVersionId(),
        location);
  }

  public String canonicalJson(JsonNode node) {
    if (node.isObject()) {
      List<java.util.Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
      entries.sort(java.util.Map.Entry.comparingByKey(CommandInputCanonicalizer::compareUtf8));
      StringBuilder result = new StringBuilder("{");
      for (int index = 0; index < entries.size(); index++) {
        if (index > 0) result.append(',');
        var entry = entries.get(index);
        result
            .append(jsonString(entry.getKey()))
            .append(':')
            .append(canonicalJson(entry.getValue()));
      }
      return result.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder result = new StringBuilder("[");
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) result.append(',');
        result.append(canonicalJson(node.get(index)));
      }
      return result.append(']').toString();
    }
    if (node.isTextual()) return jsonString(node.asText());
    if (node.isBoolean()) return Boolean.toString(node.booleanValue());
    if (node.isNull()) return "null";
    if (node.isIntegralNumber()) return node.bigIntegerValue().toString();
    if (node.isFloatingPointNumber()) {
      return node.decimalValue().toPlainString();
    }
    throw new IllegalArgumentException("지원하지 않는 JSON 값입니다.");
  }

  private CommandLocationSnapshot canonicalLocation(CommandLocation location) {
    if (location == null) return null;
    ObjectNode coarse = objectMapper.createObjectNode();
    coarse.put("type", location.coarseLocation().type());
    switch (location.coarseLocation()) {
      case CoarseLocation.Grid100m grid -> {
        coarse.put("gridX", grid.gridX());
        coarse.put("gridY", grid.gridY());
      }
      case CoarseLocation.Place place -> coarse.put("placeId", place.placeId().toString());
      case CoarseLocation.Stop stop -> coarse.put("stopId", stop.stopId().toString());
    }
    return new CommandLocationSnapshot(
        canonicalJson(coarse),
        location.coarseLocation().precisionMeters(),
        location.policyVersion(),
        location.observedAt(),
        earliestArrivedCutoff(location));
  }

  private static Instant earliestArrivedCutoff(CommandLocation location) {
    return java.util.stream.Stream.of(location.terminalAt(), location.tripEndedAt())
        .filter(Objects::nonNull)
        .filter(anchor -> !anchor.isAfter(location.evaluatedAt()))
        .map(anchor -> anchor.plus(LOCATION_TTL))
        .min(Comparator.naturalOrder())
        .orElse(null);
  }

  private static void validateClosedProjection(String runType, JsonNode input) {
    switch (runType) {
      case "itinerary_generation" -> {
        requireExactFields(input, Set.of("targetDayId", "candidateCount", "refreshExternalFacts"));
        requireUuid(input, "targetDayId");
        requireInteger(input, "candidateCount", 1, 10);
        requireBoolean(input, "refreshExternalFacts");
      }
      case "schedule_revision" -> {
        requireExactFields(input, Set.of("targetDayId", "affectedItemIds", "instructionCodes"));
        requireUuid(input, "targetDayId");
        requireUuidArray(input, "affectedItemIds");
        requireCodeArray(input, "instructionCodes");
      }
      case "itinerary_validate" -> {
        requireExactFields(input, Set.of("targetDayId"));
        requireUuid(input, "targetDayId");
      }
      case "feasibility" -> {
        requireExactFields(input, Set.of("refreshExternalFacts"));
        requireBoolean(input, "refreshExternalFacts");
      }
      case "spare_time" -> {
        requireExactFields(input, Set.of("targetDayId", "windowStart", "windowEnd"));
        requireUuid(input, "targetDayId");
        requireOffsetDateTime(input, "windowStart");
        requireOffsetDateTime(input, "windowEnd");
        if (java.time.OffsetDateTime.parse(input.get("windowEnd").asText())
            .isBefore(java.time.OffsetDateTime.parse(input.get("windowStart").asText()))) {
          throw invalidProjection();
        }
      }
      case "recovery" -> {
        requireExactFields(input, Set.of("riskEventId", "optionCount"));
        requireUuid(input, "riskEventId");
        requireInteger(input, "optionCount", 1, 10);
      }
      case "live_recalculate" -> {
        requireExactFields(input, Set.of("executionEventId", "refreshExternalFacts"));
        requireUuid(input, "executionEventId");
        requireBoolean(input, "refreshExternalFacts");
      }
      default -> throw invalidProjection();
    }
  }

  private static void requireExactFields(JsonNode input, Set<String> expected) {
    Set<String> actual = new java.util.HashSet<>();
    input.properties().forEach(property -> actual.add(property.getKey()));
    if (!actual.equals(expected)) throw invalidProjection();
  }

  private static void requireUuid(JsonNode input, String field) {
    JsonNode value = input.get(field);
    if (value == null || !value.isTextual()) throw invalidProjection();
    if (!value
        .asText()
        .matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw invalidProjection();
    }
    try {
      UUID.fromString(value.asText());
    } catch (IllegalArgumentException invalid) {
      throw invalidProjection();
    }
  }

  private static void requireBoolean(JsonNode input, String field) {
    if (!input.get(field).isBoolean()) throw invalidProjection();
  }

  private static void requireInteger(JsonNode input, String field, int minimum, int maximum) {
    JsonNode value = input.get(field);
    if (!value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < minimum
        || value.intValue() > maximum) {
      throw invalidProjection();
    }
  }

  private static void requireUuidArray(JsonNode input, String field) {
    JsonNode values = input.get(field);
    if (!values.isArray() || values.size() > 100) throw invalidProjection();
    values.forEach(
        value -> {
          if (!value.isTextual()) throw invalidProjection();
          if (!value
              .asText()
              .matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalidProjection();
          }
          try {
            UUID.fromString(value.asText());
          } catch (IllegalArgumentException invalid) {
            throw invalidProjection();
          }
        });
  }

  private static void requireCodeArray(JsonNode input, String field) {
    JsonNode values = input.get(field);
    if (!values.isArray() || values.size() > 32) throw invalidProjection();
    values.forEach(
        value -> {
          if (!value.isTextual() || !value.asText().matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw invalidProjection();
          }
        });
  }

  private static void requireOffsetDateTime(JsonNode input, String field) {
    JsonNode value = input.get(field);
    if (value == null || !value.isTextual()) throw invalidProjection();
    try {
      var matcher = CANONICAL_RFC3339.matcher(value.asText());
      if (!matcher.matches()) throw invalidProjection();
      int year = Integer.parseInt(matcher.group(1));
      int month = Integer.parseInt(matcher.group(2));
      int day = Integer.parseInt(matcher.group(3));
      int hour = Integer.parseInt(matcher.group(4));
      int minute = Integer.parseInt(matcher.group(5));
      int second = Integer.parseInt(matcher.group(6));
      int offsetHour = matcher.group(10) == null ? 0 : Integer.parseInt(matcher.group(10));
      int offsetMinute = matcher.group(11) == null ? 0 : Integer.parseInt(matcher.group(11));
      if (year < 1
          || year > 9999
          || hour > 23
          || minute > 59
          || second > 59
          || offsetHour > 18
          || offsetMinute > 59
          || (offsetHour == 18 && offsetMinute != 0)) {
        throw invalidProjection();
      }
      java.time.LocalDate.of(year, month, day);
      java.time.OffsetDateTime.parse(value.asText());
    } catch (java.time.DateTimeException | NumberFormatException invalid) {
      throw invalidProjection();
    }
  }

  private static IllegalArgumentException invalidProjection() {
    return new IllegalArgumentException(
        "structured input이 runType/schemaVersion closed schema와 일치하지 않습니다.");
  }

  private static void validateParentType(CommandInputParent parent, String runType) {
    boolean valid =
        switch (parent) {
          case CommandInputParent.Compute ignored ->
              Set.of(
                      "itinerary_validate",
                      "feasibility",
                      "spare_time",
                      "recovery",
                      "live_recalculate")
                  .contains(runType);
          case CommandInputParent.Generation ignored -> "itinerary_generation".equals(runType);
          case CommandInputParent.ScheduleRevision ignored -> "schedule_revision".equals(runType);
        };
    if (!valid) throw new IllegalArgumentException("parent와 runType이 일치하지 않습니다.");
  }

  private String jsonString(String value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (RuntimeException impossible) {
      throw new IllegalArgumentException("JSON 문자열을 canonicalize할 수 없습니다.");
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }

  private static int compareUtf8(String first, String second) {
    byte[] left = first.getBytes(StandardCharsets.UTF_8);
    byte[] right = second.getBytes(StandardCharsets.UTF_8);
    for (int index = 0; index < Math.min(left.length, right.length); index++) {
      int compared =
          Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
      if (compared != 0) return compared;
    }
    return Integer.compare(left.length, right.length);
  }
}
