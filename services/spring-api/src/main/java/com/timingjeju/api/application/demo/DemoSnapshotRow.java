package com.timingjeju.api.application.demo;

import java.util.UUID;

public record DemoSnapshotRow(
    UUID id, UUID importRunId, String operation, String parseStatus, long payloadSizeBytes) {}
