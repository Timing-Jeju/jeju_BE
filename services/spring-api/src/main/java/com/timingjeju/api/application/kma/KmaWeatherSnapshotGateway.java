package com.timingjeju.api.application.kma;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.util.UUID;

public interface KmaWeatherSnapshotGateway {
  SavedKmaWeatherSnapshot capture(
      UUID runId,
      KmaWeatherOperation operation,
      ForecastBaseTime base,
      KmaWeatherImportCommand command,
      KmaWeatherSourceResponse response);

  void markParsed(SavedKmaWeatherSnapshot snapshot);

  void markRejected(SavedKmaWeatherSnapshot snapshot);
}
