package com.timingjeju.api.global.retention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.snapshot-retention")
public record SnapshotRetentionProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("true") boolean dryRun,
    @DefaultValue("500") @Min(1) @Max(500) int batchSize) {}
