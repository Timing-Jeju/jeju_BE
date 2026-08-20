package com.timingjeju.api.application.tourapi.discovery;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

public record DiscoveryImportCommand(
    DiscoveryOperation operation,
    String keyword,
    Double longitude,
    Double latitude,
    Integer radiusMeters,
    String legalRegionCode,
    int pageBudget,
    String idempotencyKey) {

  private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  public DiscoveryImportCommand {
    if (operation == null) {
      throw new IllegalArgumentException("operation은 필수입니다.");
    }
    if (pageBudget < 1 || pageBudget > 100) {
      throw new IllegalArgumentException("pageBudget은 1에서 100 사이여야 합니다.");
    }
    if (idempotencyKey == null || !SAFE_KEY.matcher(idempotencyKey).matches()) {
      throw new IllegalArgumentException("idempotencyKey 형식이 올바르지 않습니다.");
    }
    if (!"50".equals(legalRegionCode)) {
      throw new IllegalArgumentException("제주 법정동 scope만 허용됩니다.");
    }
    if (operation == DiscoveryOperation.KEYWORD) {
      keyword = normalizeKeyword(keyword);
    } else if (keyword != null) {
      throw new IllegalArgumentException("keyword는 키워드 operation에서만 허용됩니다.");
    }
    if (operation == DiscoveryOperation.LOCATION) {
      validateLocation(longitude, latitude, radiusMeters);
    } else if (longitude != null || latitude != null || radiusMeters != null) {
      throw new IllegalArgumentException("좌표는 위치 operation에서만 허용됩니다.");
    }
  }

  public static DiscoveryImportCommand location(
      double longitude, double latitude, int radiusMeters, int pageBudget, String idempotencyKey) {
    return new DiscoveryImportCommand(
        DiscoveryOperation.LOCATION,
        null,
        longitude,
        latitude,
        radiusMeters,
        "50",
        pageBudget,
        idempotencyKey);
  }

  public static DiscoveryImportCommand keyword(
      String keyword, int pageBudget, String idempotencyKey) {
    return new DiscoveryImportCommand(
        DiscoveryOperation.KEYWORD, keyword, null, null, null, "50", pageBudget, idempotencyKey);
  }

  public static DiscoveryImportCommand stay(int pageBudget, String idempotencyKey) {
    return new DiscoveryImportCommand(
        DiscoveryOperation.STAY, null, null, null, null, "50", pageBudget, idempotencyKey);
  }

  private static String normalizeKeyword(String value) {
    if (value == null) {
      throw new IllegalArgumentException("keyword는 필수입니다.");
    }
    String normalized =
        Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
    if (normalized.isEmpty() || normalized.getBytes(StandardCharsets.UTF_8).length > 256) {
      throw new IllegalArgumentException("keyword 형식이 올바르지 않습니다.");
    }
    return normalized;
  }

  private static void validateLocation(Double longitude, Double latitude, Integer radiusMeters) {
    if (longitude == null
        || latitude == null
        || radiusMeters == null
        || !Double.isFinite(longitude)
        || !Double.isFinite(latitude)
        || longitude < 126.0
        || longitude > 127.0
        || latitude < 33.0
        || latitude > 34.0
        || radiusMeters < 1
        || radiusMeters > 20_000) {
      throw new IllegalArgumentException("제주 위치 또는 반경이 올바르지 않습니다.");
    }
  }
}
