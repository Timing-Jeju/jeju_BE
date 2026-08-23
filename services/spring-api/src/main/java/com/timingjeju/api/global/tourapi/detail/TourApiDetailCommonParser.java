package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.text.PublicPlainTextNormalizer;
import com.timingjeju.api.application.tourapi.detail.DetailCommonParser;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailCommon;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class TourApiDetailCommonParser implements DetailCommonParser {
  private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final ZoneId JEJU = ZoneId.of("Asia/Seoul");
  private final TourApiDetailJson json;
  private final OverviewPlainTextSanitizer sanitizer;
  private final PublicPlainTextNormalizer publicText;

  public TourApiDetailCommonParser(
      ObjectMapper objectMapper,
      OverviewPlainTextSanitizer sanitizer,
      PublicPlainTextNormalizer publicText) {
    this.json = new TourApiDetailJson(objectMapper);
    this.sanitizer = Objects.requireNonNull(sanitizer);
    this.publicText = Objects.requireNonNull(publicText);
  }

  @Override
  public PlaceDetailCommon parse(SnapshotPayloadFormat format, byte[] payload) {
    JsonNode item = json.item(format, payload);
    String raw = TourApiDetailJson.optionalText(item, "overview");
    return new PlaceDetailCommon(
        TourApiDetailJson.requiredText(item, "contentid"),
        TourApiDetailJson.requiredText(item, "contenttypeid"),
        publicText.normalize(TourApiDetailJson.optionalText(item, "tel")),
        TourApiDetailJson.optionalText(item, "homepage"),
        raw,
        raw == null ? null : sanitizer.sanitize(raw),
        modifiedAt(TourApiDetailJson.optionalText(item, "modifiedtime")));
  }

  private static Instant modifiedAt(String value) {
    if (value == null) return null;
    try {
      return LocalDateTime.parse(value, MODIFIED).atZone(JEJU).toInstant();
    } catch (DateTimeParseException ignored) {
      throw PlaceDetailImportException.invalidResponse();
    }
  }
}
