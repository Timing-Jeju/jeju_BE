package com.timingjeju.api.application.demo;

import java.util.UUID;

public record DemoImportResult(
    UUID runId,
    int pageCount,
    int inserted,
    int updated,
    int skipped,
    int rejected,
    boolean replayed) {}
