package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.importing.ImportRunLease;
import java.util.List;

public record TagoRouteCommitCommand(
    ImportRunLease lease,
    long expectedCheckpointVersion,
    List<TagoRouteWrite> routes,
    List<TagoRouteStopWrite> routeStops,
    List<TagoRouteLineage> lineage) {
  public TagoRouteCommitCommand {
    routes = List.copyOf(routes);
    routeStops = List.copyOf(routeStops);
    lineage = List.copyOf(lineage);
  }
}
