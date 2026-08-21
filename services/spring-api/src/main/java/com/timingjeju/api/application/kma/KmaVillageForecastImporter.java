package com.timingjeju.api.application.kma;

/** Application port used by an operational command or scheduler to import one KMA DFS grid. */
@FunctionalInterface
public interface KmaVillageForecastImporter {

  KmaWeatherImportResult importVillageForecast(KmaWeatherImportCommand command);
}
