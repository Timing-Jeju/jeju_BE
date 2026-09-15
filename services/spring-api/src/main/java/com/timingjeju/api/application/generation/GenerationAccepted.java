package com.timingjeju.api.application.generation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GenerationAccepted(
    String contractVersion,
    UUID runId,
    String status,
    String pollUrl,
    String commandInputHash,
    OffsetDateTime acceptedAt) {}
