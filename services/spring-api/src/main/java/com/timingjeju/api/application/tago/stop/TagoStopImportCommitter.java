package com.timingjeju.api.application.tago.stop;

public interface TagoStopImportCommitter {
  TagoStopCommitResult commit(TagoStopCommitCommand command);
}
