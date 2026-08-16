package com.timingjeju.api.application.kma;

@FunctionalInterface
public interface KmaWeatherRepository {
  KmaWeatherUpsertResult upsert(KmaWeatherUpsertCommand command);
}
