package com.timingjeju.api.application.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class OperatorTimetableMappingTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void draft202012_schema는_unknown_field와_schedule_route_date교차변이를_거부한다() throws Exception {
    var schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(mapper.readTree(Files.readString(schemaPath())));
    String valid = mapper.writeValueAsString(mapping101());
    assertThat(schema.validate(mapper.readTree(valid))).isEmpty();
    assertThat(
            schema.validate(
                mapper.readTree(valid.replace("\"routeNo\":\"101\"", "\"routeNo\":\"201\""))))
        .isNotEmpty();
    assertThat(
            schema.validate(
                mapper.readTree(
                    valid.replace("\"directions\":{", "\"unknown\":1,\"directions\":{"))))
        .isNotEmpty();
    assertThat(
            schema.validate(
                mapper.readTree(valid.replace("\"headers\":[", "\"unknown\":1,\"headers\":["))))
        .isNotEmpty();
    assertThat(schema.validate(mapper.readTree(valid.replace("101 남원-성산-김녕-조천-공항", "invented"))))
        .isNotEmpty();
    assertThat(schema.validate(mapper.readTree(valid.replaceFirst("\"남원\"", "\"공항\""))))
        .isNotEmpty();
  }

  @Test
  void runtime과_production_loader도_공식_topology와_closed_annotation을_독립검증한다() {
    OperatorTimetableMapping valid = mapping101();
    List<OperatorTimetableMapping.Header> changed =
        new ArrayList<>(valid.directions().get("101 남원-성산-김녕-조천-공항").headers());
    changed.set(1, new OperatorTimetableMapping.Header("invented", UUID.randomUUID()));
    Map<String, OperatorTimetableMapping.Direction> directions =
        new LinkedHashMap<>(valid.directions());
    var original = directions.get("101 남원-성산-김녕-조천-공항");
    directions.put(
        "101 남원-성산-김녕-조천-공항",
        new OperatorTimetableMapping.Direction(
            original.directionKey(),
            original.metadataDirection(),
            original.operationsSha256(),
            changed));
    assertThatThrownBy(
            () ->
                new OperatorTimetableMapping(
                    valid.version(),
                    valid.datasetId(),
                    valid.scheduleId(),
                    valid.routeNo(),
                    valid.effectiveDate(),
                    valid.routeId(),
                    valid.routeSourceProvider(),
                    valid.routeCityCode(),
                    directions))
        .isInstanceOf(IllegalArgumentException.class);

    try {
      byte[] unknownAnnotation = mapper.writeValueAsBytes(mapping101()).clone();
      String mutated =
          new String(unknownAnnotation, java.nio.charset.StandardCharsets.UTF_8)
              .replaceFirst(
                  "\"annotationOverrides\":\\{\\}",
                  "\"annotationOverrides\":{\"추정\":\"38000000-0000-0000-0000-000000000201\"}");
      assertThatThrownBy(
              () ->
                  new com.timingjeju.api.global.timetable.OperatorTimetableMappingLoader(mapper)
                      .load(mutated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .isInstanceOf(TimetableParseException.class)
          .hasMessageContaining("OPERATOR_MAPPING_SCHEMA_MISMATCH");
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static OperatorTimetableMapping mapping101() {
    return new OperatorTimetableMapping(
        "operator-mapping-v1",
        "3043887",
        "405001",
        "101",
        LocalDate.of(2024, 8, 15),
        UUID.fromString("38000000-0000-0000-0000-000000000101"),
        "TAGO",
        "39",
        Map.of(
            "101 남원-성산-김녕-조천-공항",
            direction(
                "OUT",
                "남원 →일주동로 → 공항",
                "61c996045909e469fd4db6f557b058e5ebc5ee675facb4b275002ecf08ac410a",
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
            direction(
                "IN",
                "공항 → 일주동로→ 남원",
                "cba3ad3bef5bfdcdc34f3325aa716cf20057076c0679e86b9b76b2764ed1de68",
                "공항",
                "제주 버스터미널",
                "함덕",
                "김녕",
                "세화",
                "성산",
                "고성",
                "표선",
                "남원")));
  }

  private static OperatorTimetableMapping.Direction direction(
      String key, String metadata, String hash, String... labels) {
    return new OperatorTimetableMapping.Direction(
        key,
        metadata,
        hash,
        java.util.Arrays.stream(labels)
            .map(
                label ->
                    new OperatorTimetableMapping.Header(
                        label,
                        UUID.nameUUIDFromBytes(
                            label.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .toList());
  }

  private static Path schemaPath() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null && !Files.isDirectory(current.resolve("fixtures")))
      current = current.getParent();
    if (current == null) throw new AssertionError("repository root");
    return current.resolve("fixtures/jeju-timetable/operator-mapping-v1.schema.json");
  }
}
