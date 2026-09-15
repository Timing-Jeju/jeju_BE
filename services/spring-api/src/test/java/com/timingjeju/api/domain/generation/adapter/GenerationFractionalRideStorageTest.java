package com.timingjeju.api.domain.generation.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.generation.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("unit")
class GenerationFractionalRideStorageTest {
  @Test
  void 초단위_승차시간도_거부없이_구간_저장까지_도달한다() {
    var jdbc =
        new JdbcTemplate() {
          @Override
          public int update(String sql, Object... args) {
            if (sql.contains("insert into public.trip_legs")) {
              assertThat(args[10]).isEqualTo(5);
              assertThat(args[11]).isNull();
              assertThat(args[13]).isEqualTo(16);
              assertThat(args[17].toString())
                  .contains("\"rideNanos\":630000000000", "\"roundingNanos\":30000000000");
              throw new IllegalStateException("leg_projection_reached");
            }
            return 1;
          }
        };
    var dayId = UUID.randomUUID();
    var placeId = UUID.randomUUID();
    var start = OffsetDateTime.parse("2026-10-01T10:00:00+09:00");
    var end = start.plusMinutes(16);
    var event =
        new GenerationTimeline.Event(
            "bus", 1, "transfer", start, end, 16, null, null, "bus", 1000, List.of("timetable"));
    var day =
        new GenerationScheduleDay(
            dayId,
            List.of(
                new GenerationScheduleDay.Item(
                    1, placeId, "custom", start, start, 0, "day_start", null),
                new GenerationScheduleDay.Item(2, placeId, "custom", end, end, 0, "day_end", null)),
            List.of(new GenerationScheduleDay.Connection(1, 2, List.of(event))));
    var candidate = mock(GenerationCandidateProjection.Candidate.class);
    when(candidate.scheduleDay()).thenReturn(day);
    when(candidate.transfers())
        .thenReturn(
            List.of(
                new GenerationTransfer(
                    "bus",
                    "bus",
                    1000,
                    List.of(
                        new GenerationTransfer.Walk("access_walk", 0, 0, List.of("access")),
                        new GenerationTransfer.Walk("egress_walk", 0, 0, List.of("egress"))),
                    List.of(
                        new GenerationTransfer.Ride(
                            "stop-a",
                            "stop-b",
                            start.plusMinutes(5),
                            start.plusMinutes(15).plusSeconds(30),
                            2,
                            List.of("timetable"))),
                    null,
                    List.of("timetable"))));
    var timeline = mock(GenerationTimeline.class);
    when(timeline.risks()).thenReturn(List.of());
    when(candidate.timeline()).thenReturn(timeline);
    var input = mock(GenerationTripInput.class);
    var boundary = mock(GenerationDayBoundary.class);
    when(boundary.dayId()).thenReturn(dayId);
    when(boundary.dayNo()).thenReturn(1);
    when(input.boundary()).thenReturn(boundary);
    when(input.tripId()).thenReturn(UUID.randomUUID());
    when(input.places()).thenReturn(List.of());
    var snapshot = mock(GenerationTripSnapshot.class);
    when(snapshot.input()).thenReturn(input);
    assertThatThrownBy(
            () ->
                new JdbcGenerationCandidateWriter(jdbc)
                    .write(UUID.randomUUID(), snapshot, candidate, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("leg_projection_reached");
  }
}
