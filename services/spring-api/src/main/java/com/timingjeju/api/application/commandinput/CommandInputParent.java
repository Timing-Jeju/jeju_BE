package com.timingjeju.api.application.commandinput;

import java.util.Objects;
import java.util.UUID;

public sealed interface CommandInputParent
    permits CommandInputParent.Compute,
        CommandInputParent.Generation,
        CommandInputParent.ScheduleRevision {

  UUID id();

  String databaseColumn();

  record Compute(UUID id) implements CommandInputParent {
    public Compute {
      Objects.requireNonNull(id, "compute run id는 필수입니다.");
    }

    @Override
    public String databaseColumn() {
      return "compute_run_id";
    }
  }

  record Generation(UUID id) implements CommandInputParent {
    public Generation {
      Objects.requireNonNull(id, "generation run id는 필수입니다.");
    }

    @Override
    public String databaseColumn() {
      return "generation_run_id";
    }
  }

  record ScheduleRevision(UUID id) implements CommandInputParent {
    public ScheduleRevision {
      Objects.requireNonNull(id, "schedule revision run id는 필수입니다.");
    }

    @Override
    public String databaseColumn() {
      return "schedule_revision_run_id";
    }
  }
}
