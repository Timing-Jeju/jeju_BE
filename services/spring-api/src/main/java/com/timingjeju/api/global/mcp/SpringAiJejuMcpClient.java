package com.timingjeju.api.global.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public final class SpringAiJejuMcpClient implements McpToolClient {
  private final McpSyncClient client;
  private final McpContractGuard contractGuard;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final McpCallResilience resilience;
  private final AtomicBoolean ready = new AtomicBoolean();

  @Autowired
  public SpringAiJejuMcpClient(
      @Qualifier("jejuPlannerMcpSyncClient") McpSyncClient client,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      McpCallResilience resilience) {
    this.client = Objects.requireNonNull(client, "client는 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry는 필수입니다.");
    this.resilience = Objects.requireNonNull(resilience, "resilience는 필수입니다.");
    this.contractGuard = new McpContractGuard(objectMapper, McpExpectedCatalog.load(objectMapper));
  }

  SpringAiJejuMcpClient(
      McpSyncClient client,
      McpContractGuard contractGuard,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      McpCallResilience resilience) {
    this.client = Objects.requireNonNull(client, "client는 필수입니다.");
    this.contractGuard = Objects.requireNonNull(contractGuard, "contractGuard는 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry는 필수입니다.");
    this.resilience = Objects.requireNonNull(resilience, "resilience는 필수입니다.");
  }

  @PostConstruct
  void verifyServerContract() {
    try {
      McpResilientResult<McpSchema.ListToolsResult> discovery =
          resilience.execute(
              () -> {
                if (!client.isInitialized()) client.initialize();
                return client.listTools();
              });
      List<McpDiscoveredTool> tools =
          discovery.value().tools().stream()
              .map(
                  tool ->
                      new McpDiscoveredTool(
                          tool.name(),
                          tool.inputSchema(),
                          tool.outputSchema() == null ? Map.of() : tool.outputSchema()))
              .toList();
      contractGuard.verifyCatalog(tools);
      ready.set(true);
    } catch (RuntimeException exception) {
      ready.set(false);
      if (exception instanceof McpContractException contractException) {
        throw contractException;
      }
      throw new McpRemoteCallException("MCP_INITIALIZATION_FAILED", exception);
    }
  }

  @Override
  public McpInvocationResult call(McpInvocation invocation) {
    if (!ready.get()) throw new McpRemoteCallException("MCP_NOT_READY");
    Map<String, Object> arguments =
        contractGuard.validateArguments(
            invocation.toolName(), invocation.arguments(), invocation.outboundIdAllowlist());
    String mcpInputHash = McpSchemaFingerprint.sha256(arguments, objectMapper);
    Timer.Sample sample = Timer.start(meterRegistry);
    String status = "succeeded";
    try {
      McpResilientResult<McpSchema.CallToolResult> call =
          resilience.execute(
              () -> {
                McpSchema.CallToolResult result =
                    client.callTool(
                        new McpSchema.CallToolRequest(invocation.toolName(), arguments));
                if (Boolean.TRUE.equals(result.isError())) {
                  throw new McpRemoteCallException("MCP_TOOL_ERROR");
                }
                return result;
              });
      Map<String, Object> structuredContent =
          contractGuard.validateStructuredContent(
              invocation.toolName(),
              call.value().structuredContent(),
              invocation.inboundIdAllowlist());
      return new McpInvocationResult(structuredContent, mcpInputHash, call.attemptCount());
    } catch (McpContractException exception) {
      status = "contract_invalid";
      throw exception;
    } catch (McpRemoteCallException exception) {
      status = "remote_error";
      throw exception;
    } catch (RuntimeException exception) {
      status = "transport_error";
      throw new McpRemoteCallException("MCP_COMPUTE_UNAVAILABLE", exception);
    } finally {
      sample.stop(
          Timer.builder("mcp.client.call")
              .tag("tool", invocation.toolName())
              .tag("status", status)
              .register(meterRegistry));
    }
  }

  @Override
  public boolean isReady() {
    return ready.get();
  }
}
