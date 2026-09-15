package com.timingjeju.api.domain.schedule.adapter;

import com.timingjeju.api.application.schedule.ScheduleException;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 저장된 정확한 시간과 nullable 분 표현의 일치 여부를 검증한다. */
final class ScheduleLegPrecisionProjection {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long MINUTE = 60_000_000_000L;

  private ScheduleLegPrecisionProjection() {}

  static void validate(
      String facts,
      String mode,
      Integer walk,
      Integer wait,
      Integer ride,
      Integer interchange,
      Integer duration) {
    try {
      var root = facts == null ? MAPPER.createObjectNode() : MAPPER.readTree(facts);
      var generation = root.path("generation");
      var precision = generation.get("precision");
      if (walk == null
          || interchange == null
          || duration == null
          || walk < 0
          || interchange < 0
          || duration < 0) throw invalid();
      if (precision == null) {
        if (wait == null
            || ride == null
            || wait < 0
            || ride < 0
            || (long) walk + wait + ride + interchange != duration) throw invalid();
        return;
      }
      if (!"public_transit".equals(mode)
          || !precision.isObject()
          || precision.size() != 5
          || !generation.path("schemaVersion").isIntegralNumber()
          || generation.path("schemaVersion").intValue() != 1) throw invalid();
      long walking = nanos(precision, "walkNanos");
      long waiting = nanos(precision, "waitNanos");
      long riding = nanos(precision, "rideNanos");
      long changing = nanos(precision, "transferNanos");
      long rounding = nanos(precision, "roundingNanos");
      if (rounding >= MINUTE
          || walking != walk * MINUTE
          || changing != interchange * MINUTE
          || !Objects.equals(exactMinutes(waiting), wait)
          || !Objects.equals(exactMinutes(riding), ride)
          || walking + waiting + riding + changing + rounding != duration * MINUTE) throw invalid();
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static long nanos(JsonNode precision, String key) {
    var value = precision.get(key);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
    long nanos = value.longValue();
    if (nanos < 0 || nanos > 1440L * MINUTE) throw invalid();
    return nanos;
  }

  private static Integer exactMinutes(long nanos) {
    return nanos % MINUTE == 0 ? Math.toIntExact(nanos / MINUTE) : null;
  }

  private static ScheduleException invalid() {
    return ScheduleException.internalServerError();
  }
}
