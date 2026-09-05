package com.timingjeju.api.application.schedule;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ReorderScheduleCommandTest {
  private static final UUID VERSION = UUID.fromString("49000000-0000-0000-0000-000000000003");

  @Test
  void nested_null은_NPE가_아닌_INVALID_REQUEST다() {
    assertInvalid(() -> new ReorderScheduleCommand(VERSION, null));
    assertInvalid(
        () ->
            new ReorderScheduleCommand(
                VERSION, Arrays.asList((ReorderScheduleCommand.DayOrder) null)));
    assertInvalid(() -> new ReorderScheduleCommand.DayOrder(1, null));
    assertInvalid(() -> new ReorderScheduleCommand.DayOrder(1, Arrays.asList((UUID) null)));
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("INVALID_REQUEST");
  }
}
