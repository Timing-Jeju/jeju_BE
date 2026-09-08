package com.timingjeju.api.application.commandinput.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CommandLocationCleanupContractsTest {

  @Test
  void batchSize_경계는_1과500만_포함한다() {
    Instant now = Instant.parse("2026-08-24T12:00:00Z");

    assertThatCode(() -> new CommandLocationCleanupCommand(now, 1, Duration.ofMillis(1)))
        .doesNotThrowAnyException();
    assertThatCode(() -> new CommandLocationCleanupCommand(now, 500, Duration.ofMinutes(1)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new CommandLocationCleanupCommand(now, 0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CommandLocationCleanupCommand(now, 501, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CommandLocationCleanupCommand(now, 500, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CommandLocationCleanupCommand(now, 500, Duration.ofMinutes(1).plusMillis(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cycle은_10batch_3retry_1분_deadline을_상한으로_고정한다() {
    assertThatCode(
            () ->
                new CommandLocationCleanupCycleCommand(
                    500, 10, 3, Duration.ofMillis(250), Duration.ofMinutes(1)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new CommandLocationCleanupCycleCommand(
                    500, 11, 3, Duration.ofMillis(250), Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CommandLocationCleanupCycleCommand(
                    500, 10, 4, Duration.ofMillis(250), Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CommandLocationCleanupCycleCommand(
                    500, 10, 3, Duration.ofMillis(250), Duration.ofMinutes(1).plusMillis(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failureCode는_closed분류값만_허용해_raw값의_metric_tag유입을_막는다() {
    assertThat(CommandLocationCleanupCycleResult.class.getRecordComponents()[5].getType())
        .isEqualTo(CommandLocationCleanupException.Code.class);
  }
}
