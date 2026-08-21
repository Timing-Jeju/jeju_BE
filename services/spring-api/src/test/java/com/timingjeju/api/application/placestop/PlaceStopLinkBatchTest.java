package com.timingjeju.api.application.placestop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PlaceStopLinkBatchTest {

  private static final UUID PLACE = UUID.fromString("37000000-0000-0000-0000-000000000001");
  private static final UUID STOP = UUID.fromString("37000000-0000-0000-0000-000000000002");
  private static final Instant OBSERVED_AT = Instant.parse("2026-08-16T06:00:00Z");
  private static final String FINGERPRINT =
      "3737373737373737373737373737373737373737373737373737373737373737";

  @Test
  void 변경된_장소와_정류장_scope를_불변값으로_보존한다() {
    var batch =
        new PlaceStopLinkBatch(
            Set.of(PLACE), Set.of(STOP), "postgis:tago", OBSERVED_AT, FINGERPRINT, true);

    assertThat(batch.changedPlaceIds()).containsExactly(PLACE);
    assertThat(batch.changedStopIds()).containsExactly(STOP);
    assertThat(batch.complete()).isTrue();
  }

  @Test
  void partial_batch도_허용하지만_빈_scope는_거부한다() {
    var partial =
        new PlaceStopLinkBatch(
            Set.of(PLACE), Set.of(), "postgis:tago", OBSERVED_AT, FINGERPRINT, false);

    assertThat(partial.complete()).isFalse();
    assertThatThrownBy(
            () ->
                new PlaceStopLinkBatch(
                    Set.of(), Set.of(), "postgis:tago", OBSERVED_AT, FINGERPRINT, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope");
  }

  @Test
  void blank_provider와_비정상_fingerprint를_거부한다() {
    assertThatThrownBy(
            () ->
                new PlaceStopLinkBatch(
                    Set.of(PLACE), Set.of(), " ", OBSERVED_AT, FINGERPRINT, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceProvider");
    assertThatThrownBy(
            () ->
                new PlaceStopLinkBatch(
                    Set.of(PLACE), Set.of(), "postgis:tago", OBSERVED_AT, "not-a-hash", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fingerprint");
  }
}
