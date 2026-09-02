package com.timingjeju.api.global.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mcp")
public record McpPrivateProperties(
    boolean enabled,
    URI baseUrl,
    String allowedHost,
    String issuer,
    String audience,
    String subject,
    String scope,
    Path signingKeyDescriptorFile,
    Duration tokenLifetime,
    Duration requestTimeout,
    Integer maxAttempts,
    Duration retryDelay,
    Integer circuitFailureThreshold,
    Duration circuitOpenDuration) {}
