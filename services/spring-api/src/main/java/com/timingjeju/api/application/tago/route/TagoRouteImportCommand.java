package com.timingjeju.api.application.tago.route;

import java.util.HashSet;
import java.util.List;

public record TagoRouteImportCommand(String idempotencyKey, List<String> routeNumbers) {
  public TagoRouteImportCommand {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.length() > 255
        || routeNumbers == null
        || routeNumbers.isEmpty()) throw TagoRouteImportException.invalidRequest();
    routeNumbers = routeNumbers.stream().map(String::trim).toList();
    if (routeNumbers.stream().anyMatch(value -> value.isBlank() || value.length() > 30)
        || new HashSet<>(routeNumbers).size() != routeNumbers.size())
      throw TagoRouteImportException.invalidRequest();
  }
}
