package com.timingjeju.api.application.staypolicy;

public record StayPolicyImportResult(
    String version, String payloadHash, int importedPolicyCount, boolean dryRun) {}
