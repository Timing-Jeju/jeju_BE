package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.importing.ImportRunCounts;

public record TagoRouteCommitResult(ImportRunCounts counts, long checkpointVersion) {}
