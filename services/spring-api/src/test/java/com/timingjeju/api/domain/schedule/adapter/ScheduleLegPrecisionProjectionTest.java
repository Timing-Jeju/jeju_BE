package com.timingjeju.api.domain.schedule.adapter;

import static org.assertj.core.api.Assertions.*;

import com.timingjeju.api.application.schedule.ScheduleException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ScheduleLegPrecisionProjectionTest {
  private static final String FACTS =
      """
      {"generation":{"schemaVersion":1,"precision":{
      "walkNanos":0,"rideNanos":630000000000,"transferNanos":0,
      "waitNanos":300000000000,"roundingNanos":30000000000}}}
      """;

  @Test
  void 초단위_승차는_null_분과_정확한_분해가_함께_있을때만_허용한다() {
    assertThatCode(
            () ->
                ScheduleLegPrecisionProjection.validate(FACTS, "public_transit", 0, 5, null, 0, 16))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> ScheduleLegPrecisionProjection.validate(FACTS, "public_transit", 0, 5, 10, 0, 16))
        .isInstanceOf(ScheduleException.class);
  }

  @Test
  void 정밀도_없는_null과_합계_불일치는_거절한다() {
    assertThatThrownBy(
            () ->
                ScheduleLegPrecisionProjection.validate("{}", "public_transit", 0, 5, null, 0, 16))
        .isInstanceOf(ScheduleException.class);
    assertThatThrownBy(
            () ->
                ScheduleLegPrecisionProjection.validate(FACTS, "public_transit", 0, 5, null, 0, 17))
        .isInstanceOf(ScheduleException.class);
  }

  @Test
  void 기존_정수분_일정은_정확한_합계일때_허용한다() {
    assertThatCode(() -> ScheduleLegPrecisionProjection.validate("{}", "walk", 10, 0, 0, 0, 10))
        .doesNotThrowAnyException();
  }
}
