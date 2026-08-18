package com.timingjeju.api.application.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DemoStorageViewTest {
  @Test
  void 조회_모델은_테이블_흐름을_명시한다() {
    UUID runId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    DemoStorageView view =
        new DemoStorageView(
            List.of(
                new DemoRunRow(
                    runId, "tour_api", "areaBasedList2", "succeeded", 2, 2, Instant.EPOCH)),
            List.of(new DemoSnapshotRow(UUID.randomUUID(), runId, "areaBasedList2", "parsed", 123)),
            List.of(
                new DemoPlaceRow(
                    UUID.randomUUID(),
                    runId,
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    assertThat(view.tableFlow())
        .containsExactly(
            "data_import_runs",
            "external_api_snapshots",
            "tour_places",
            "place_details",
            "place_detail_items",
            "place_images",
            "tour_api_operation_provenance");
    assertThat(view.places().getFirst().contentId()).isEqualTo("10001");
  }
}
