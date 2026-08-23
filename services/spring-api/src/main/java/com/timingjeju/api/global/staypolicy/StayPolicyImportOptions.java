package com.timingjeju.api.global.staypolicy;

import java.nio.file.Path;
import java.time.Instant;

public record StayPolicyImportOptions(
    Path importRoot,
    Path importFile,
    String version,
    Instant effectiveAt,
    String expectedActiveVersion,
    boolean dryRun) {}
