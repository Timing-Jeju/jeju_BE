package com.timingjeju.api.application.commandinput;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Worker가 MCP tool arguments를 조립하기 직전에 호출하는 위치 재검증 경계입니다. */
public class McpCommandLocationResolver {
  private final CommandInputSnapshotRepository repository;
  private final Clock clock;

  public McpCommandLocationResolver(CommandInputSnapshotRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  public Optional<CommandLocationSnapshot> resolveImmediatelyBeforeMcp(CommandInputParent parent) {
    Objects.requireNonNull(parent, "parent는 필수입니다.");
    return repository.findUsableLocation(parent, clock.instant().truncatedTo(ChronoUnit.MICROS));
  }
}
