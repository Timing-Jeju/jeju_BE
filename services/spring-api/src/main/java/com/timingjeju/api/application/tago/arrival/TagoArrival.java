package com.timingjeju.api.application.tago.arrival;

public record TagoArrival(
    String externalRouteId,
    String routeNo,
    String routeType,
    String vehicleType,
    int estimatedArrivalSeconds,
    int remainingStops) {

  public TagoArrival {
    externalRouteId = requireText(externalRouteId, "externalRouteId", 512);
    routeNo = requireText(routeNo, "routeNo", 64);
    routeType = optionalText(routeType, "routeType", 128);
    vehicleType = optionalText(vehicleType, "vehicleType", 128);
    if (estimatedArrivalSeconds < 0 || estimatedArrivalSeconds > 86_400) {
      throw TagoArrivalException.invalidResponse();
    }
    if (remainingStops < 0 || remainingStops > 10_000) {
      throw TagoArrivalException.invalidResponse();
    }
  }

  private static String requireText(String value, String name, int maxLength) {
    String normalized = optionalText(value, name, maxLength);
    if (normalized == null) throw new IllegalArgumentException(name + "는 필수입니다.");
    return normalized;
  }

  private static String optionalText(String value, String name, int maxLength) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.strip();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(name + " 길이가 제한을 초과했습니다.");
    }
    return normalized;
  }
}
