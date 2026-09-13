package com.timingjeju.api.domain.generation.controller;

import com.timingjeju.api.application.generation.CreateGenerationCommand;
import com.timingjeju.api.application.generation.GenerationException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class GenerationRequestCodec {
  static final int MAX_BODY_BYTES = 16384;
  private static final Set<String> FIELDS =
      Set.of("targetDayId", "expectedActiveScheduleVersionId", "candidateCount");
  private final ObjectReader reader;

  GenerationRequestCodec(ObjectMapper mapper) {
    reader =
        mapper
            .readerFor(JsonNode.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  CreateGenerationCommand decode(byte[] body) {
    if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
      throw GenerationException.invalidRequest();
    }
    JsonNode root;
    try {
      root = reader.readValue(body);
    } catch (JacksonException failure) {
      throw GenerationException.invalidRequest();
    }
    if (root == null || !root.isObject() || !root.propertyNames().equals(FIELDS)) {
      throw GenerationException.invalidRequest();
    }
    var count = root.get("candidateCount");
    if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() != 3) {
      throw GenerationException.invalidRequest();
    }
    var active = root.get("expectedActiveScheduleVersionId");
    return new CreateGenerationCommand(
        uuid(root.get("targetDayId")), active.isNull() ? null : uuid(active), 3);
  }

  byte[] canonicalBody(CreateGenerationCommand command) {
    String active =
        command.expectedActiveScheduleVersionId() == null
            ? "null"
            : "\"" + command.expectedActiveScheduleVersionId() + "\"";
    return ("{\"candidateCount\":3,\"expectedActiveScheduleVersionId\":"
            + active
            + ",\"targetDayId\":\""
            + command.targetDayId()
            + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static UUID uuid(JsonNode value) {
    if (!value.isString()) throw GenerationException.invalidRequest();
    try {
      UUID result = UUID.fromString(value.asText());
      if (!result.toString().equals(value.asText())) throw GenerationException.invalidRequest();
      return result;
    } catch (IllegalArgumentException failure) {
      throw GenerationException.invalidRequest();
    }
  }
}
