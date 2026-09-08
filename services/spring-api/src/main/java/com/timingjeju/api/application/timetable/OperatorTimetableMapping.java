package com.timingjeju.api.application.timetable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OperatorTimetableMapping(
    String version,
    String datasetId,
    String scheduleId,
    String routeNo,
    LocalDate effectiveDate,
    UUID routeId,
    String routeSourceProvider,
    String routeCityCode,
    Map<String, Direction> directions) {
  public OperatorTimetableMapping {
    directions = Map.copyOf(new LinkedHashMap<>(directions));
    boolean canonical101 =
        scheduleId.equals("405001")
            && routeNo.equals("101")
            && effectiveDate.equals(LocalDate.of(2024, 8, 15));
    boolean canonical201 =
        scheduleId.equals("405009")
            && routeNo.equals("201")
            && effectiveDate.equals(LocalDate.of(2024, 8, 1));
    if (!version.equals("operator-mapping-v1")
        || !datasetId.equals("3043887")
        || !routeSourceProvider.equals("TAGO")
        || !routeCityCode.equals("39")
        || !(canonical101 || canonical201)
        || directions.size() != 2) {
      throw new IllegalArgumentException("operator mapping allowlist mismatch");
    }
    validateOfficialTopology(scheduleId, directions);
  }

  private static void validateOfficialTopology(
      String scheduleId, Map<String, Direction> directions) {
    Map<String, OfficialDirection> expected =
        scheduleId.equals("405001")
            ? Map.of(
                "101 남원-성산-김녕-조천-공항",
                official(
                    "OUT",
                    "남원 →일주동로 → 공항",
                    "첫차(성산일출봉 출발) 6:00, 막차 21:44, 배차간격 28분~43분, 금남여객(064-753-4423)",
                    "남원",
                    "표선",
                    "고성",
                    "성산",
                    "세화",
                    "김녕",
                    "함덕",
                    "제주 버스터미널",
                    "공항"),
                "101 공항-조천-김녕-성산-남원",
                official(
                    "IN",
                    "공항 → 일주동로→ 남원",
                    "첫차(성산일출봉 출발) 6:05, 막차 21:38, 배차간격 30분~120분, 금남여객(064-753-4423)",
                    "공항",
                    "제주 버스터미널",
                    "함덕",
                    "김녕",
                    "세화",
                    "성산",
                    "고성",
                    "표선",
                    "남원"))
            : Map.of(
                "201 서귀포터미널-남원-성산-세화-조천-제주터미널",
                official(
                    "OUT",
                    "서귀포터미널→일주동로 →제주터미널",
                    "첫차(성산일출봉 출발) 5:43, 막차 21:35, 배차간격 15~22분, 금남여객(064-753-4423)",
                    "서귀포버스터미널",
                    "서귀포중앙R",
                    "효돈초교(경유)",
                    "남원",
                    "표선",
                    "신산",
                    "고성",
                    "",
                    "성산",
                    "오조(경유)",
                    "세화고(경유)",
                    "세화",
                    "김녕",
                    "함덕",
                    "제주버스터미널"),
                "201 제주터미널-조천-세화-성산-남원-서귀포터미널",
                official(
                    "IN",
                    "제주터미널→일주동로 →서귀포터미널",
                    "첫차(성산일출봉 출발) 5:55, 막차 21:35, 배차간격 15~20분, 금남여객(064-753-4423)",
                    "제주버스터미널",
                    "함덕",
                    "김녕",
                    "세화",
                    "세화고(경유)",
                    "오조(경유)",
                    "성산",
                    "고성",
                    "",
                    "신산",
                    "표선",
                    "남원",
                    "효돈초교(경유)",
                    "서귀포중앙R",
                    "서귀포버스터미널"));
    if (!directions.keySet().equals(expected.keySet())) {
      throw new IllegalArgumentException("official sheet allowlist mismatch");
    }
    expected.forEach(
        (sheet, contract) -> {
          Direction actual = directions.get(sheet);
          List<String> labels = actual.headers().stream().map(Header::exactLabel).toList();
          if (!actual.directionKey().equals(contract.key())
              || !actual.metadataDirection().equals(contract.metadata())
              || !actual.operationsSha256().equals(contract.operationsHash())
              || !labels.equals(contract.labels())) {
            throw new IllegalArgumentException("official topology allowlist mismatch");
          }
          for (int index = 0; index < actual.headers().size(); index++) {
            boolean ignored = contract.labels().get(index).isEmpty();
            if (actual.headers().get(index).unresolved() != ignored) {
              throw new IllegalArgumentException("official unresolved column mismatch");
            }
          }
        });
  }

  private static OfficialDirection official(
      String key, String metadata, String operations, String... labels) {
    return new OfficialDirection(key, metadata, hash(operations), List.of(labels));
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record OfficialDirection(
      String key, String metadata, String operationsHash, List<String> labels) {}

  public record Direction(
      String directionKey,
      String metadataDirection,
      String operationsSha256,
      List<Header> headers) {
    public Direction {
      headers = List.copyOf(headers);
      if (headers.isEmpty() || !operationsSha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("direction mapping is invalid");
      }
    }
  }

  public record Header(
      String exactLabel, UUID stopId, boolean unresolved, Map<String, UUID> annotationOverrides) {
    public Header(String exactLabel, UUID stopId) {
      this(exactLabel, stopId, false, Map.of());
    }

    public Header {
      annotationOverrides = Map.copyOf(annotationOverrides);
      if (unresolved == (stopId != null)) {
        throw new IllegalArgumentException("column rule must be NORMAL or IGNORE_UNRESOLVED");
      }
    }
  }
}
