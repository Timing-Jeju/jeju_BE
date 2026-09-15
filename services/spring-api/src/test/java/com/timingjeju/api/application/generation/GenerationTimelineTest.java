package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class GenerationTimelineTest {
  @Test
  void 후보_장소목록과_실제_체류장소가_다르면_거부한다() {
    var candidate = candidate();
    candidate.putArray("place_ids").add("tourapi.place:2");
    assertThatThrownBy(() -> GenerationTimeline.from(candidate, scope(), bindings))
        .isInstanceOf(GenerationException.class);
  }

  @Test
  void 상세_유형과_방문_상세시간_변조를_거부한다() {
    for (var field :
        List.of("stay_minutes", "departure_at", "arrival_at", "entry_at", "place_id", "visit")) {
      var candidate = candidate();
      var event = (ObjectNode) candidate.get("timeline").get(0);
      var visit = (ObjectNode) event.get("visit");
      switch (field) {
        case "stay_minutes" -> visit.put(field, 999);
        case "place_id" -> visit.put(field, "tourapi.place:2");
        case "visit" -> event.putNull(field);
        default -> visit.put(field, "2026-10-01T10:31:00+09:00");
      }
      assertThatThrownBy(() -> GenerationTimeline.from(candidate, scope(), bindings))
          .isInstanceOf(GenerationException.class);
    }
  }

  @Test
  void 이동거리와_근거있는_위험코드는_원문없이_보존하고_허용외_수단은_거부한다() {
    var candidate = candidate();
    var transfer = ((ObjectNode) candidate.get("timeline").get(0)).deepCopy();
    transfer
        .put("event_id", "event-2")
        .put("sequence", 2)
        .put("type", "transfer")
        .put("start_at", "2026-10-01T10:30:00+09:00")
        .put("end_at", "2026-10-01T10:40:00+09:00")
        .put("duration_minutes", 10)
        .putNull("place_id");
    transfer.putObject("transfer").put("mode", "walk").put("distance_meters", 400);
    transfer.remove("visit");
    ((tools.jackson.databind.node.ArrayNode) candidate.get("timeline")).add(transfer);
    candidate.put("day_end_at", "2026-10-01T10:40:00+09:00");
    ((ObjectNode) candidate.get("totals")).put("total_minutes", 40).put("transfer_minutes", 10);
    var risk = candidate.putArray("segment_risks").addObject();
    risk.put("event_id", "event-2").put("risk", "medium").put("slack_minutes", 5);
    risk.putArray("reason_codes").add("SYNTHETIC_RISK");
    risk.putArray("evidence_fact_ids").add("fact-1");
    var result = GenerationTimeline.from(candidate, scope(), bindings);
    assertThat(result.events().getLast().distanceMeters()).isEqualTo(400);
    assertThat(result.risks().getFirst().level()).isEqualTo("medium");
    assertThat(result.risks().getFirst().reasonCodes()).containsExactly("SYNTHETIC_RISK");
    ((ObjectNode) transfer.get("transfer")).put("mode", "taxi");
    assertThatThrownBy(() -> GenerationTimeline.from(candidate, scope(), bindings))
        .isInstanceOf(GenerationException.class);
    ((ObjectNode) transfer.get("transfer")).put("mode", "walk");
    transfer.put("start_at", "2026-10-01T10:20:00+09:00").put("duration_minutes", 20);
    assertThatThrownBy(() -> GenerationTimeline.from(candidate, scope(), bindings))
        .isInstanceOf(GenerationException.class);
  }

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final UUID place = UUID.fromString("79000000-0000-0000-0000-000000000001");
  private final GenerationPlaceBindings bindings =
      new GenerationPlaceBindings(List.of(new GenerationPlaceBindings.Place(place, "1", "공식 장소")));

  @Test
  void 이벤트_시간과_체류시간_근거를_확인하고_원문없이_추출한다() {
    var result = GenerationTimeline.from(candidate(), scope(), bindings);
    assertThat(result.events()).hasSize(1);
    assertThat(result.events().getFirst().placeId()).isEqualTo(place);
    assertThat(result.events().getFirst().durationMinutes()).isEqualTo(30);
    assertThat(mapper.writeValueAsString(result))
        .doesNotContain("synthetic original", "geometry", "title");
  }

  @Test
  void 시간_겹침_체류시간_변조_미지근거와_다른_경계를_거부한다() {
    for (var field :
        List.of("duration_minutes", "sequence", "evidence_fact_ids", "place_id", "start_at")) {
      var candidate = candidate();
      var event = (ObjectNode) candidate.get("timeline").get(0);
      switch (field) {
        case "duration_minutes" -> event.put(field, 20);
        case "sequence" -> event.put(field, 0);
        case "evidence_fact_ids" -> event.putArray(field).add("unknown");
        case "place_id" -> event.put(field, "tourapi.place:999");
        default -> event.put(field, "2026-10-01T09:00:00+09:00");
      }
      assertThatThrownBy(() -> GenerationTimeline.from(candidate, scope(), bindings))
          .isInstanceOf(GenerationException.class);
    }
    var candidate = candidate();
    candidate.put("end_place_id", "tourapi.place:999");
    assertThatThrownBy(() -> GenerationTimeline.from(candidate, scope(), bindings))
        .isInstanceOf(GenerationException.class);
  }

  @Test
  void 집계_불일치와_미지_위험이벤트는_거부한다() {
    var wrongTotal = candidate();
    ((ObjectNode) wrongTotal.get("totals")).put("total_minutes", 31);
    assertThatThrownBy(() -> GenerationTimeline.from(wrongTotal, scope(), bindings))
        .isInstanceOf(GenerationException.class);
    var invalid = candidate();
    invalid.putArray("segment_risks").addObject().put("event_id", "unknown").put("risk", "low");
    assertThatThrownBy(() -> GenerationTimeline.from(invalid, scope(), bindings))
        .isInstanceOf(GenerationException.class);
  }

  private GenerationTimeline.Scope scope() {
    return new GenerationTimeline.Scope(
        OffsetDateTime.parse("2026-10-01T10:00:00+09:00"),
        OffsetDateTime.parse("2026-10-01T18:00:00+09:00"),
        "tourapi.place:1",
        "tourapi.place:1",
        Set.of("walk"),
        Map.of("tourapi.place:1", 30),
        Set.of("fact-1"));
  }

  private ObjectNode candidate() {
    return (ObjectNode)
        mapper.valueToTree(
            Map.of(
                "start_place_id",
                "tourapi.place:1",
                "place_ids",
                List.of("tourapi.place:1"),
                "end_place_id",
                "tourapi.place:1",
                "day_start_at",
                "2026-10-01T10:00:00+09:00",
                "day_end_at",
                "2026-10-01T10:30:00+09:00",
                "timeline",
                List.of(
                    Map.of(
                        "event_id",
                        "event-1",
                        "sequence",
                        1,
                        "type",
                        "visit",
                        "start_at",
                        "2026-10-01T10:00:00+09:00",
                        "end_at",
                        "2026-10-01T10:30:00+09:00",
                        "duration_minutes",
                        30,
                        "place_id",
                        "tourapi.place:1",
                        "evidence_fact_ids",
                        List.of("fact-1"),
                        "title",
                        "synthetic original",
                        "visit",
                        Map.of(
                            "place_id",
                            "tourapi.place:1",
                            "arrival_at",
                            "2026-10-01T10:00:00+09:00",
                            "entry_at",
                            "2026-10-01T10:00:00+09:00",
                            "departure_at",
                            "2026-10-01T10:30:00+09:00",
                            "stay_minutes",
                            30,
                            "evidence_fact_ids",
                            List.of("fact-1")))),
                "totals",
                Map.of(
                    "total_minutes",
                    30,
                    "visit_minutes",
                    30,
                    "transfer_minutes",
                    0,
                    "rest_minutes",
                    0,
                    "meal_minutes",
                    0,
                    "buffer_minutes",
                    0),
                "segment_risks",
                List.of()));
  }
}
