package com.timingjeju.api.application.tago.arrival;

final class TagoArrivalReplayExpiredException extends RuntimeException {
  private TagoArrivalReplayExpiredException() {
    super("ARRIVAL_REPLAY_EXPIRED", null, false, false);
  }

  static TagoArrivalReplayExpiredException create() {
    return new TagoArrivalReplayExpiredException();
  }
}
