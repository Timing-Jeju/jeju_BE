package com.timingjeju.api.application.commandinput;

import java.time.Instant;
import java.util.Optional;

public interface CommandInputSnapshotRepository {
  CommandInputSnapshot save(CommandInputSnapshot snapshot);

  Optional<CommandInputSnapshot> find(CommandInputParent parent);

  Optional<CommandLocationSnapshot> findUsableLocation(
      CommandInputParent parent, Instant evaluatedAt);
}
