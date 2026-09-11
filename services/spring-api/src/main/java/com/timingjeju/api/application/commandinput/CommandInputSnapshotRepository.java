package com.timingjeju.api.application.commandinput;

import java.util.Optional;

public interface CommandInputSnapshotRepository {
  CommandInputSnapshot save(CommandInputSnapshot snapshot);

  Optional<CommandInputSnapshot> find(CommandInputParent parent);
}
