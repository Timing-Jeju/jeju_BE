package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLease;

public record StartedTagoRouteImport(
    ImportRunLease lease,
    boolean replayed,
    long checkpointVersion,
    int routeCount,
    int routeStopCount,
    ImportRunCounts counts) {}
