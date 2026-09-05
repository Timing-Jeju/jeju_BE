package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.trip.ReplaceTripPreferencesCommand;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.domain.trip.dto.request.ReplaceTripPreferencesRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;

final class TripPreferencesRequestCodec {
  private final ObjectReader reader;

  TripPreferencesRequestCodec(ObjectMapper objectMapper) {
    this.reader =
        objectMapper
            .rebuild()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(
                String.class,
                coercion -> {
                  coercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                  coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                  coercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                })
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
            .readerFor(ReplaceTripPreferencesRequest.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  ReplaceTripPreferencesCommand decode(byte[] body) {
    try {
      ReplaceTripPreferencesRequest request = reader.readValue(body);
      if (request == null) {
        throw TripException.invalidRequest();
      }
      return request.toCommand();
    } catch (JacksonException | TripException failure) {
      throw TripException.invalidRequest();
    }
  }
}
