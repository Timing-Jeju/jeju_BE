package com.timingjeju.api.application.kma;

import java.time.Instant;
import java.util.List;

public record KmaWeatherBatch(
    int nx,
    int ny,
    int rawItemCount,
    Instant sourceWatermarkAt,
    List<KmaWeatherObservation> observations,
    List<KmaWeatherForecast> forecasts) {

  public KmaWeatherBatch {
    observations = List.copyOf(observations);
    forecasts = List.copyOf(forecasts);
  }
}
