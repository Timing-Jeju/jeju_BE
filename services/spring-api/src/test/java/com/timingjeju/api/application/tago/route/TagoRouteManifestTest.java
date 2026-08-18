package com.timingjeju.api.application.tago.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoRouteManifestTest {
  private static final UUID RUN = UUID.fromString("36000000-0000-0000-0000-000000000301");
  private static final UUID SNAPSHOT = UUID.fromString("36000000-0000-0000-0000-000000000302");
  private static final Instant OBSERVED_AT = Instant.parse("2026-08-16T00:00:00Z");

  @Test
  void canonical_manifest는_동일_route와_sequence만_true_replay로_판정한다() {
    TagoRoute route = route("급행", "공항", "성산");
    TagoRouteManifest original = TagoRouteManifest.incoming(route, stops("STOP-1", "STOP-2"));

    assertThat(TagoRouteManifest.incoming(route, stops("STOP-1", "STOP-2"))).isEqualTo(original);
    assertThat(TagoRouteManifest.incoming(route("일반", "공항", "성산"), stops("STOP-1", "STOP-2")))
        .isNotEqualTo(original);
    assertThat(TagoRouteManifest.incoming(route, stops("STOP-2", "STOP-1"))).isNotEqualTo(original);
  }

  private static TagoRoute route(String type, String start, String end) {
    return new TagoRoute("39", "R-1", "101", type, start, end, "R-1");
  }

  private static List<TagoRouteStopWrite> stops(String first, String second) {
    return List.of(stop(first, 1), stop(second, 2));
  }

  private static TagoRouteStopWrite stop(String node, int sequence) {
    return new TagoRouteStopWrite(
        new TagoRouteStop("39", "R-1", node, sequence), "R-1", SNAPSHOT, RUN, OBSERVED_AT);
  }
}
