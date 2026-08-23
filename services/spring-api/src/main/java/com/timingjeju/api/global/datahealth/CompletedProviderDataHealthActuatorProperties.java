package com.timingjeju.api.global.datahealth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.data-health.actuator")
public record CompletedProviderDataHealthActuatorProperties(boolean enabled) {}
