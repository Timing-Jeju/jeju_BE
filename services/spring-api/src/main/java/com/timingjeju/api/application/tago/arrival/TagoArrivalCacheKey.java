package com.timingjeju.api.application.tago.arrival;

import java.util.UUID;

public record TagoArrivalCacheKey(
    String provider, String service, UUID stopId, String cityCode, String nodeId) {
  private static final String TAGO_PROVIDER = "TAGO";
  private static final String TAGO_SERVICE = "ArvlInfoInqireService";

  public TagoArrivalCacheKey {
    provider = requireText(provider, "provider", 128);
    service = requireText(service, "service", 128);
    if (stopId == null) throw new IllegalArgumentException("stopId는 필수입니다.");
    cityCode = requireText(cityCode, "cityCode", 64);
    nodeId = requireText(nodeId, "nodeId", 512);
  }

  public static TagoArrivalCacheKey tago(UUID stopId, String cityCode, String nodeId) {
    return new TagoArrivalCacheKey(TAGO_PROVIDER, TAGO_SERVICE, stopId, cityCode, nodeId);
  }

  private static String requireText(String value, String name, int maxLength) {
    if (value == null || value.isBlank()) throw TagoArrivalException.invalidRequest();
    String normalized = value.strip();
    if (normalized.length() > maxLength) throw TagoArrivalException.invalidRequest();
    return normalized;
  }
}
