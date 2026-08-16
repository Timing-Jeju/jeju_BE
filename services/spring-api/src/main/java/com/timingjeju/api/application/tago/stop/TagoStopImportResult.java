package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.importing.ImportRunCounts;
import java.util.UUID;

public record TagoStopImportResult(
    UUID runId,
    String cityCode,
    int stationCount,
    int pageCount,
    ImportRunCounts counts,
    long checkpointVersion,
    boolean replayed) {}
