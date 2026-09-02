package com.timingjeju.api.global.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
  private final McpCallAuditWriter auditWriter;
  private final AtomicBoolean ready = new AtomicBoolean();

  @Autowired
  public SpringAiJejuMcpClient(
      @Qualifier("jejuPlannerMcpSyncClient") McpSyncClient client,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      McpCallResilience resilience,
      McpCallAuditWriter auditWriter) {
    this.client = Objects.requireNonNull(client, "client는 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry는 필수입니다.");
    this.resilience = Objects.requireNonNull(resilience, "resilience는 필수입니다.");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter는 필수입니다.");
    this.contractGuard = new McpContractGuard(objectMapper, McpExpectedCatalog.load(objectMapper));
  }

  SpringAiJejuMcpClient(
      McpSyncClient client,
      McpContractGuard contractGuard,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      McpCallResilience resilience,
      McpCallAuditWriter auditWriter) {
    this.client = Objects.requireNonNull(client, "client는 필수입니다.");
    this.contractGuard = Objects.requireNonNull(contractGuard, "contractGuard는 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry는 필수입니다.");
    this.resilience = Objects.requireNonNull(resilience, "resilience는 필수입니다.");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter는 필수입니다.");
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
      throw McpFailureClassifier.classify(exception);
    }
  }

  @Override
  public McpInvocationResult call(McpInvocation invocation) {
    if (!ready.get()) throw new McpRemoteCallException("MCP_NOT_READY");
    Map<String, Object> hashArguments = new LinkedHashMap<>(invocation.arguments());
    hashArguments.put("requestId", invocation.requestId());
    String mcpInputHash = McpSchemaFingerprint.sha256(hashArguments, objectMapper);
    Map<String, Object> wireArguments = new LinkedHashMap<>(hashArguments);
    wireArguments.put("inputHash", mcpInputHash);
    Map<String, Object> arguments =
        contractGuard.validateArguments(
            invocation.toolName(), wireArguments, invocation.outboundIdAllowlist());
    String schemaChecksum = contractGuard.schemaChecksum(invocation.toolName());
    int requestFactCount = McpFactCounter.count(arguments, objectMapper);
    AtomicInteger attempt = new AtomicInteger();
    AtomicLong finalAttemptLatencyMs = new AtomicLong();
    Timer.Sample sample = Timer.start(meterRegistry);
    String status = "succeeded";
    try {
      McpResilientResult<McpSchema.CallToolResult> call =
          resilience.execute(
              () -> {
                int attemptNo = attempt.incrementAndGet();
                long startedAt = System.nanoTime();
                try {
                  McpSchema.CallToolResult result =
                      client.callTool(
                          new McpSchema.CallToolRequest(invocation.toolName(), arguments));
                  finalAttemptLatencyMs.set(elapsedMillis(startedAt));
                  if (Boolean.TRUE.equals(result.isError())) {
                    McpRemoteCallException error =
                        new McpRemoteCallException("MCP_TOOL_ERROR", false);
                    recordAudit(
                        invocation,
                        mcpInputHash,
                        schemaChecksum,
                        requestFactCount,
                        0,
                        attemptNo,
                        "domain_failure",
                        Math.toIntExact(finalAttemptLatencyMs.get()),
                        error.stableCode());
                    throw error;
                  }
                  return result;
                } catch (McpRemoteCallException exception) {
                  throw exception;
                } catch (RuntimeException exception) {
                  McpRemoteCallException classified = McpFailureClassifier.classify(exception);
                  recordAudit(
                      invocation,
                      mcpInputHash,
                      schemaChecksum,
                      requestFactCount,
                      0,
                      attemptNo,
                      auditStatus(classified),
                      Math.toIntExact(elapsedMillis(startedAt)),
                      classified.stableCode());
                  throw classified;
                }
              });
      Map<String, Object> structuredContent;
      try {
        structuredContent =
            contractGuard.validateStructuredContent(
                invocation.toolName(),
                call.value().structuredContent(),
                invocation.inboundIdAllowlist());
      } catch (McpContractException exception) {
        recordAudit(
            invocation,
            mcpInputHash,
            schemaChecksum,
            requestFactCount,
            0,
            call.attemptCount(),
            "contract_invalid",
            Math.toIntExact(finalAttemptLatencyMs.get()),
            exception.stableCode());
        throw exception;
      }
      recordAudit(
          invocation,
          mcpInputHash,
          schemaChecksum,
          requestFactCount,
          McpFactCounter.count(structuredContent, objectMapper),
          call.attemptCount(),
          "succeeded",
          Math.toIntExact(finalAttemptLatencyMs.get()),
          null);
      return new McpInvocationResult(structuredContent, mcpInputHash, call.attemptCount());
    } catch (McpContractException exception) {
      status = "contract_invalid";
      throw exception;
    } catch (McpRemoteCallException exception) {
      status = "remote_error";
      if (attempt.get() == 0) {
        recordAudit(
            invocation,
            mcpInputHash,
            schemaChecksum,
            requestFactCount,
            0,
            1,
            auditStatus(exception),
            0,
            exception.stableCode());
      }
      throw exception;
    } catch (RuntimeException exception) {
      status = "transport_error";
      throw McpFailureClassifier.classify(exception);
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

  private void recordAudit(
      McpInvocation invocation,
      String mcpInputHash,
      String schemaChecksum,
      int requestFactCount,
      int responseFactCount,
      int attemptNo,
      String status,
      int latencyMs,
      String errorCode) {
    auditWriter.record(
        new McpCallAudit(
            invocation.parent(),
            invocation.requestId(),
            invocation.toolName(),
            contractGuard.contractVersion(),
            invocation.commandInputHash(),
            mcpInputHash,
            schemaChecksum,
            requestFactCount,
            responseFactCount,
            attemptNo,
            status,
            latencyMs,
            errorCode));
  }

  private static String auditStatus(McpRemoteCallException exception) {
    return switch (exception.stableCode()) {
      case "MCP_AUTHENTICATION_FAILED" -> "authentication_failed";
      case "MCP_PROTOCOL_INVALID" -> "protocol_invalid";
      default -> "transport_error";
    };
  }

  private static long elapsedMillis(long startedAt) {
    return Math.min(Integer.MAX_VALUE, Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L));
  }
}
