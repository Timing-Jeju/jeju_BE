package com.timingjeju.api.global.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpPrivateProperties.class)
public class McpPrivateClientConfiguration {

  @Bean
  McpServiceJwtIssuer mcpServiceJwtIssuer(McpPrivateProperties properties) {
    McpEndpointPolicy.requirePrivateHttps(properties.baseUrl(), properties.allowedHost());
    Duration lifetime =
        properties.tokenLifetime() == null ? Duration.ofMinutes(2) : properties.tokenLifetime();
    return new McpServiceJwtIssuer(
        properties.issuer(),
        properties.audience(),
        properties.subject(),
        properties.scope(),
        properties.keyId(),
        McpPemPrivateKeyLoader.load(properties.privateKeyFile()),
        lifetime,
        Clock.systemUTC(),
        UUID::randomUUID);
  }

  @Bean(destroyMethod = "close")
  McpSyncClient jejuPlannerMcpSyncClient(
      McpPrivateProperties properties, McpServiceJwtIssuer jwtIssuer, JsonMapper jsonMapper) {
    WebClient.Builder authenticatedClient =
        WebClient.builder()
            .baseUrl(properties.baseUrl().toString())
            .filter(McpPrivateRequestFilter.create(jwtIssuer));
    var transport =
        WebClientStreamableHttpTransport.builder(authenticatedClient)
            .endpoint("/mcp")
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .build();
    Duration timeout =
        properties.requestTimeout() == null ? Duration.ofSeconds(35) : properties.requestTimeout();
    McpSyncClient client =
        McpClient.sync(transport)
            .clientInfo(McpSchema.Implementation.builder("timing-jeju-spring", "0.7.0").build())
            .requestTimeout(timeout)
            .build();
    return client;
  }

  @Bean
  McpCallResilience mcpCallResilience(McpPrivateProperties properties) {
    int maxAttempts = properties.maxAttempts() == null ? 3 : properties.maxAttempts();
    Duration retryDelay =
        properties.retryDelay() == null ? Duration.ofMillis(200) : properties.retryDelay();
    int failureThreshold =
        properties.circuitFailureThreshold() == null ? 5 : properties.circuitFailureThreshold();
    Duration openDuration =
        properties.circuitOpenDuration() == null
            ? Duration.ofSeconds(30)
            : properties.circuitOpenDuration();
    return new McpCallResilience(
        maxAttempts,
        retryDelay,
        failureThreshold,
        openDuration,
        System::nanoTime,
        duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000));
  }
}
