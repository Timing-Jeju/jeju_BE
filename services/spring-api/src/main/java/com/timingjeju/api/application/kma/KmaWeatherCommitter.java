package com.timingjeju.api.application.kma;

@FunctionalInterface
public interface KmaWeatherCommitter {
  KmaWeatherCommitResult commit(KmaWeatherCommitCommand command);
}
