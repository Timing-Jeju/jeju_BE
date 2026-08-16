package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.importing.ImportRunCounts;

public record TagoStopCommitResult(ImportRunCounts counts, long checkpointVersion) {}
