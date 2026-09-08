package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.domain.trip.exception.TripPlacePreferencesProblemDefinitions;
import com.timingjeju.api.global.error.ProblemCodeRegistry;
import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class TripPlacePreferencesProblemWriter {
  private final ProblemResponseWriter writer;
  private final ProblemCodeRegistry fallback;
  private final TripPlacePreferencesProblemDefinitions definitions;

  TripPlacePreferencesProblemWriter(
      ProblemResponseWriter writer,
      ProblemCodeRegistry fallback,
      TripPlacePreferencesProblemDefinitions definitions) {
    this.writer = writer;
    this.fallback = fallback;
    this.definitions = definitions;
  }

  void write(HttpServletRequest request, HttpServletResponse response, String code)
      throws IOException {
    ProblemDefinition definition = definitions.find(code);
    writer.write(
        request, response, definition == null ? fallback.find(code) : definition, List.of());
  }
}
