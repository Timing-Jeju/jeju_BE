package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GenerationLegPrecisionTest {
  @Test
  void 환승_도보와_각_승차전_대기를_초단위로_분리한다() {
    var start = OffsetDateTime.parse("2026-10-01T10:00:00+09:00");
    var event =
        new GenerationTimeline.Event(
            "bus",
            1,
            "transfer",
            start,
            start.plusMinutes(25),
            25,
            null,
            null,
            "bus",
            1000,
            List.of("timetable"));
    var transfer =
        new GenerationTransfer(
            "bus",
            "bus",
            1000,
            List.of(
                new GenerationTransfer.Walk("access_walk", 2, 100, List.of("access")),
                new GenerationTransfer.Walk("transfer_walk", 1, 100, List.of("interchange")),
                new GenerationTransfer.Walk("egress_walk", 3, 200, List.of("egress"))),
            List.of(
                new GenerationTransfer.Ride(
                    "a",
                    "b",
                    start.plusMinutes(5).plusSeconds(15),
                    start.plusMinutes(12).plusSeconds(30),
                    2,
                    List.of("first")),
                new GenerationTransfer.Ride(
                    "b",
                    "c",
                    start.plusMinutes(15),
                    start.plusMinutes(21).plusSeconds(30),
                    1,
                    List.of("second"))),
            null,
            List.of("timetable"));
    var value = GenerationLegPrecision.from(event, transfer);
    assertThat(value.rideNanos()).isEqualTo(825_000_000_000L);
    assertThat(value.waitNanos()).isEqualTo(285_000_000_000L);
    assertThat(value.transferNanos()).isEqualTo(60_000_000_000L);
    assertThat(value.walkNanos()).isEqualTo(300_000_000_000L);
    assertThat(value.roundingNanos()).isEqualTo(30_000_000_000L);
    assertThat(value.rideMinutes()).isNull();
    assertThat(value.waitMinutes()).isNull();
  }

  @Test
  void 범위를_넘는_시간은_산술_예외_대신_계약_위반으로_거절한다() {
    assertThatThrownBy(() -> new GenerationLegPrecision(Long.MAX_VALUE, 1, 0, 0, 0))
        .isInstanceOf(GenerationException.class);
  }

  @Test
  void 초단위_승차와_대기와_계획_올림여유를_서로_섞지_않는다() {
    var start = OffsetDateTime.parse("2026-10-01T10:00:00+09:00");
    var event =
        new GenerationTimeline.Event(
            "bus",
            1,
            "transfer",
            start,
            start.plusMinutes(21),
            21,
            null,
            null,
            "bus",
            1000,
            List.of("timetable"));
    var transfer =
        new GenerationTransfer(
            "bus",
            "bus",
            1000,
            List.of(
                new GenerationTransfer.Walk("access_walk", 2, 100, List.of("access")),
                new GenerationTransfer.Walk("egress_walk", 3, 200, List.of("egress"))),
            List.of(
                new GenerationTransfer.Ride(
                    "a",
                    "b",
                    start.plusMinutes(5),
                    start.plusMinutes(17).plusSeconds(30),
                    2,
                    List.of("timetable"))),
            null,
            List.of("timetable"));
    var value = GenerationLegPrecision.from(event, transfer);
    assertThat(value.rideNanos()).isEqualTo(750_000_000_000L);
    assertThat(value.waitNanos()).isEqualTo(180_000_000_000L);
    assertThat(value.walkNanos()).isEqualTo(300_000_000_000L);
    assertThat(value.roundingNanos()).isEqualTo(30_000_000_000L);
    assertThat(value.rideMinutes()).isNull();
    assertThat(value.waitMinutes()).isEqualTo(3);
  }
}
