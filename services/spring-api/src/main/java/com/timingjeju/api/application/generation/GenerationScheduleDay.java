package com.timingjeju.api.application.generation;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 검증된 하루를 일정 항목과 인접 연결로 분할한다. 버퍼를 체류시간으로 바꾸지 않는다. */
public record GenerationScheduleDay(UUID dayId, List<Item> items, List<Connection> connections) {
  public GenerationScheduleDay {
    items = List.copyOf(items);
    connections = List.copyOf(connections);
  }

  public static GenerationScheduleDay from(
      GenerationTimeline timeline, GenerationDayBoundary boundary) {
    var events = timeline.events();
    if (events.isEmpty()) throw invalid();
    var start = events.getFirst().startAt();
    var end = events.getLast().endAt();
    if (start.isBefore(boundary.startAt()) || end.isAfter(boundary.endAt())) throw invalid();
    var items = new ArrayList<Item>();
    var connections = new ArrayList<Connection>();
    var pending = new ArrayList<GenerationTimeline.Event>();
    items.add(new Item(1, boundary.startPlaceId(), "custom", start, start, 0, "day_start", null));
    for (var event : events) {
      if (event.type().equals("transfer") || event.type().equals("buffer")) {
        pending.add(event);
        continue;
      }
      String type =
          switch (event.type()) {
            case "visit" -> "place_visit";
            case "meal" -> "meal";
            case "rest" -> "free_time";
            default -> throw invalid();
          };
      if (event.placeId() == null || event.durationMinutes() <= 0) throw invalid();
      var next =
          new Item(
              items.size() + 1,
              event.placeId(),
              type,
              event.startAt(),
              event.endAt(),
              event.durationMinutes(),
              null,
              event.eventId());
      connections.add(connect(items.getLast(), next, pending));
      items.add(next);
      pending.clear();
    }
    if (items.size() == 1) throw invalid();
    var terminal =
        new Item(items.size() + 1, boundary.endPlaceId(), "custom", end, end, 0, "day_end", null);
    connections.add(connect(items.getLast(), terminal, pending));
    items.add(terminal);
    return new GenerationScheduleDay(boundary.dayId(), items, connections);
  }

  private static Connection connect(Item from, Item to, List<GenerationTimeline.Event> events) {
    if (from.endAt().isAfter(to.startAt())) throw invalid();
    int transfers = 0;
    var cursor = from.endAt();
    for (var event : events) {
      if (event.startAt().isBefore(cursor) || event.endAt().isAfter(to.startAt())) throw invalid();
      cursor = event.endAt();
      if (event.type().equals("transfer")) transfers++;
    }
    if (transfers > 1 || (transfers == 0 && !from.placeId().equals(to.placeId()))) throw invalid();
    return new Connection(from.sequenceNo(), to.sequenceNo(), events);
  }

  public record Item(
      int sequenceNo,
      UUID placeId,
      String itemType,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      int stayMinutes,
      String boundaryRole,
      String eventId) {}

  public record Connection(
      int fromSequenceNo, int toSequenceNo, List<GenerationTimeline.Event> events) {
    public Connection {
      events = List.copyOf(events);
    }
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
