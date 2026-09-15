package com.timingjeju.api.application.generation;

import java.time.Duration;

/** 검증된 버스 시각의 정확한 분해. 계획 종료 올림 여유는 실제 대기와 구분한다. */
public record GenerationLegPrecision(
    long walkNanos, long rideNanos, long transferNanos, long waitNanos, long roundingNanos) {
  private static final long MINUTE = 60_000_000_000L;
  private static final long MAX_DURATION = 1440L * MINUTE;

  public GenerationLegPrecision {
    if (walkNanos < 0
        || rideNanos < 0
        || transferNanos < 0
        || waitNanos < 0
        || roundingNanos < 0
        || roundingNanos >= MINUTE
        || walkNanos > MAX_DURATION
        || rideNanos > MAX_DURATION
        || transferNanos > MAX_DURATION
        || waitNanos > MAX_DURATION) throw invalid();
    long total =
        Math.addExact(
            Math.addExact(walkNanos, rideNanos),
            Math.addExact(transferNanos, Math.addExact(waitNanos, roundingNanos)));
    if (total > 1440L * MINUTE || total % MINUTE != 0) throw invalid();
  }

  public Integer rideMinutes() {
    return exactMinutes(rideNanos);
  }

  public Integer waitMinutes() {
    return exactMinutes(waitNanos);
  }

  public static GenerationLegPrecision from(
      GenerationTimeline.Event event, GenerationTransfer transfer) {
    if (!"bus".equals(transfer.mode())
        || !"bus".equals(event.mode())
        || !event.eventId().equals(transfer.eventId())
        || transfer.rides().isEmpty()) throw invalid();
    var access = transfer.walks().stream().filter(w -> w.kind().equals("access_walk")).toList();
    var egress = transfer.walks().stream().filter(w -> w.kind().equals("egress_walk")).toList();
    var interchanges =
        transfer.walks().stream().filter(w -> w.kind().equals("transfer_walk")).toList();
    if (access.size() != 1
        || egress.size() != 1
        || interchanges.size() != transfer.rides().size() - 1
        || transfer.walks().size() != 2 + interchanges.size()
        || transfer.walks().stream().anyMatch(w -> w.plannedMinutes() < 0)) throw invalid();
    var reached = event.startAt().plusMinutes(access.getFirst().plannedMinutes());
    long riding = 0, waiting = 0, interchange = 0;
    for (int i = 0; i < transfer.rides().size(); i++) {
      var ride = transfer.rides().get(i);
      long wait = Duration.between(reached, ride.departureAt()).toNanos();
      long duration = Duration.between(ride.departureAt(), ride.arrivalAt()).toNanos();
      if (wait < 0 || duration <= 0) throw invalid();
      waiting = Math.addExact(waiting, wait);
      riding = Math.addExact(riding, duration);
      reached = ride.arrivalAt();
      if (i < interchanges.size()) {
        int minutes = interchanges.get(i).plannedMinutes();
        interchange = Math.addExact(interchange, Math.multiplyExact((long) minutes, MINUTE));
        reached = reached.plusMinutes(minutes);
      }
    }
    reached = reached.plusMinutes(egress.getFirst().plannedMinutes());
    long walking =
        Math.multiplyExact(
            (long) access.getFirst().plannedMinutes() + egress.getFirst().plannedMinutes(), MINUTE);
    var result =
        new GenerationLegPrecision(
            walking,
            riding,
            interchange,
            waiting,
            Duration.between(reached, event.endAt()).toNanos());
    if (Duration.between(event.startAt(), event.endAt()).toNanos()
        != Math.multiplyExact((long) event.durationMinutes(), MINUTE)) throw invalid();
    return result;
  }

  private static Integer exactMinutes(long nanos) {
    return nanos % MINUTE == 0 ? Math.toIntExact(nanos / MINUTE) : null;
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
