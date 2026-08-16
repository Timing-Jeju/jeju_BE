package com.timingjeju.api.application.tago.route;

import java.util.Comparator;
import java.util.List;

public record TagoRouteManifest(
    String cityCode,
    String externalRouteId,
    String routeNo,
    String routeType,
    String directionName,
    String directionKey,
    List<Stop> stops) {
  public TagoRouteManifest {
    stops = List.copyOf(stops);
  }

  public static TagoRouteManifest incoming(TagoRoute route, List<TagoRouteStopWrite> routeStops) {
    List<Stop> canonicalStops =
        routeStops.stream()
            .map(
                write -> {
                  if (!route.externalRouteId().equals(write.stop().externalRouteId())
                      || !route.directionKey().equals(write.directionKey()))
                    throw TagoRouteImportException.invalidResponse();
                  return new Stop(write.stop().nodeId(), write.stop().stopSequence());
                })
            .sorted(Comparator.comparingInt(Stop::sequence))
            .toList();
    return stored(
        route.cityCode(),
        route.externalRouteId(),
        route.routeNo(),
        route.routeType(),
        route.startNodeName() + " → " + route.endNodeName(),
        route.directionKey(),
        canonicalStops);
  }

  public static TagoRouteManifest stored(
      String cityCode,
      String externalRouteId,
      String routeNo,
      String routeType,
      String directionName,
      String directionKey,
      List<Stop> stops) {
    return new TagoRouteManifest(
        cityCode,
        externalRouteId,
        routeNo,
        routeType,
        directionName,
        directionKey,
        stops.stream().sorted(Comparator.comparingInt(Stop::sequence)).toList());
  }

  public record Stop(String nodeId, int sequence) {}
}
