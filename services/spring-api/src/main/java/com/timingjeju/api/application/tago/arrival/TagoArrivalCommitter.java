package com.timingjeju.api.application.tago.arrival;

@FunctionalInterface
public interface TagoArrivalCommitter {
  TagoArrivalCommitResult commit(TagoArrivalCommitCommand command);
}
