package com.timingjeju.api.global.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpPrivateProperties.class)
public class McpPrivateClientConfiguration {

  @Bean(destroyMethod = "close")
  HttpClient jejuMcpHttpClient(
      @Value("${app.mcp.tls-trust-certificate-file:}") String certificateFile) {
    return McpTlsHttpClient.create(certificateFile);
  }

  @Bean
  McpServiceJwtIssuer mcpServiceJwtIssuer(McpPrivateProperties properties, JsonMapper jsonMapper) {
    McpEndpointPolicy.requirePrivateHttps(properties.baseUrl(), properties.allowedHost());
    Duration lifetime =
        properties.tokenLifetime() == null ? Duration.ofMinutes(2) : properties.tokenLifetime();
    return new McpServiceJwtIssuer(
        properties.issuer(),
        properties.audience(),
        properties.subject(),
        properties.scope(),
        new ReloadingMcpSigningKeyProvider(properties.signingKeyDescriptorFile(), jsonMapper),
        lifetime,
        Clock.systemUTC(),
        UUID::randomUUID);
  }

  @Bean(destroyMethod = "close")
  McpSyncClient jejuPlannerMcpSyncClient(
      McpPrivateProperties properties,
      McpServiceJwtIssuer jwtIssuer,
      JsonMapper jsonMapper,
      @Qualifier("jejuMcpHttpClient") HttpClient httpClient) {
    Duration timeout =
        properties.requestTimeout() == null ? Duration.ofSeconds(35) : properties.requestTimeout();
    return buildClient(properties, jwtIssuer, jsonMapper, timeout, httpClient);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "app.schedule-generation", name = "enabled", havingValue = "true")
  McpSyncClient jejuPlannerGenerationMcpSyncClient(
      McpPrivateProperties properties,
      McpServiceJwtIssuer jwtIssuer,
      JsonMapper jsonMapper,
      @Qualifier("jejuMcpHttpClient") HttpClient httpClient,
      @Value("${app.mcp.generation-request-timeout:165s}") Duration timeout) {
    return buildClient(
        properties, jwtIssuer, jsonMapper, resolveRequestTimeout(timeout), httpClient);
  }

  private McpSyncClient buildClient(
      McpPrivateProperties properties,
      McpServiceJwtIssuer jwtIssuer,
      JsonMapper jsonMapper,
      Duration timeout,
      HttpClient httpClient) {
    WebClient.Builder authenticatedClient =
        WebClient.builder()
            // Three candidates include both text and structured evidence in the MCP envelope.
            // Keep memory bounded without truncating valid responses at WebFlux's 256 KiB default.
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .clientConnector(new JdkClientHttpConnector(httpClient))
            .baseUrl(properties.baseUrl().toString())
            .filter(McpPrivateRequestFilter.create(jwtIssuer));
    var transport =
        WebClientStreamableHttpTransport.builder(authenticatedClient)
            .endpoint("/mcp")
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .build();
    McpSyncClient client =
        McpClient.sync(transport)
            .clientInfo(McpSchema.Implementation.builder("timing-jeju-spring", "0.7.0").build())
            .requestTimeout(timeout)
            .build();
    return client;
  }

  static Duration resolveRequestTimeout(Duration configured) {
    Duration minimum = Duration.ofSeconds(165);
    if (configured != null && configured.compareTo(minimum) < 0) {
      throw new IllegalArgumentException("MCP 요청 제한시간은 165초 이상이어야 합니다.");
    }
    return configured == null ? minimum : configured;
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
