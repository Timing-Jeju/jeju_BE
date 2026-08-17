package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.importing.ImportRunCounts;
import java.util.UUID;

public record TagoRouteImportResult(
    UUID runId,
    int routeCount,
    int routeStopCount,
    int snapshotCount,
    ImportRunCounts counts,
    long checkpointVersion,
    boolean replayed) {}
