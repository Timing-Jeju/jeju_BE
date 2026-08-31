package com.timingjeju.api.global.mcp;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class McpContractGuard {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private final ObjectMapper objectMapper;
  private final McpExpectedCatalog expectedCatalog;
  private final Map<String, Schema> inputSchemas = new HashMap<>();
  private final Map<String, Schema> outputSchemas = new HashMap<>();

  public McpContractGuard(ObjectMapper objectMapper, McpExpectedCatalog expectedCatalog) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    this.expectedCatalog = Objects.requireNonNull(expectedCatalog, "expectedCatalog는 필수입니다.");
  }

  public static McpContractGuard forSingleTool(
      ObjectMapper objectMapper, String toolName, Map<String, Object> outputSchema) {
    return forSingleTool(objectMapper, toolName, outputSchema, outputSchema);
  }

  static McpContractGuard forSingleTool(
      ObjectMapper objectMapper,
      String toolName,
      Map<String, Object> inputSchema,
      Map<String, Object> outputSchema) {
    String inputChecksum = McpSchemaFingerprint.sha256(inputSchema, objectMapper);
    String outputChecksum = McpSchemaFingerprint.sha256(outputSchema, objectMapper);
    McpExpectedCatalog catalog =
        new McpExpectedCatalog(
            "test",
            Map.of(
                toolName,
                new McpExpectedCatalog.ExpectedTool(toolName, inputChecksum, outputChecksum)));
    McpContractGuard guard = new McpContractGuard(objectMapper, catalog);
    guard.inputSchemas.put(toolName, compileSchema(objectMapper, inputSchema));
    guard.outputSchemas.put(toolName, compileSchema(objectMapper, outputSchema));
    return guard;
  }

  public synchronized void verifyCatalog(List<McpDiscoveredTool> discoveredTools) {
    Objects.requireNonNull(discoveredTools, "discoveredTools는 필수입니다.");
    Map<String, McpDiscoveredTool> discovered = new LinkedHashMap<>();
    for (McpDiscoveredTool tool : discoveredTools) {
      if (discovered.putIfAbsent(tool.name(), tool) != null) throw invalidContract();
    }
    if (!discovered.keySet().equals(expectedCatalog.tools().keySet())) throw invalidContract();

    Map<String, Schema> verifiedInputSchemas = new HashMap<>();
    Map<String, Schema> verifiedOutputSchemas = new HashMap<>();
    for (var entry : expectedCatalog.tools().entrySet()) {
      McpDiscoveredTool actual = discovered.get(entry.getKey());
      var expected = entry.getValue();
      if (!expected
              .inputSchemaSha256()
              .equals(McpSchemaFingerprint.sha256(actual.inputSchema(), objectMapper))
          || !expected
              .outputSchemaSha256()
              .equals(McpSchemaFingerprint.sha256(actual.outputSchema(), objectMapper))) {
        throw invalidContract();
      }
      verifiedInputSchemas.put(actual.name(), compileSchema(objectMapper, actual.inputSchema()));
      verifiedOutputSchemas.put(actual.name(), compileSchema(objectMapper, actual.outputSchema()));
    }
    inputSchemas.clear();
    inputSchemas.putAll(verifiedInputSchemas);
    outputSchemas.clear();
    outputSchemas.putAll(verifiedOutputSchemas);
  }

  public Map<String, Object> validateArguments(
      String toolName,
      Map<String, Object> arguments,
      Map<String, Set<String>> outboundIdAllowlist) {
    Schema schema = inputSchemas.get(toolName);
    if (schema == null) throw invalidContract();
    JsonNode content = objectMapper.valueToTree(arguments);
    if (!content.isObject() || !schema.validate(content).isEmpty()) throw invalidContract();
    validateIds(content, outboundIdAllowlist);
    return Map.copyOf(objectMapper.convertValue(content, MAP_TYPE));
  }

  public Map<String, Object> validateStructuredContent(
      String toolName, Object structuredContent, Map<String, Set<String>> inboundIdAllowlist) {
    Schema schema = outputSchemas.get(toolName);
    if (schema == null) throw invalidContract();
    JsonNode content = objectMapper.valueToTree(structuredContent);
    if (!content.isObject()) {
      throw new McpContractException("MCP_CONTRACT_INVALID: structuredContent must be object");
    }
    if (!schema.validate(content).isEmpty()) throw invalidContract();
    validateIds(content, inboundIdAllowlist);
    return Map.copyOf(objectMapper.convertValue(content, MAP_TYPE));
  }

  private static Schema compileSchema(ObjectMapper objectMapper, Map<String, Object> outputSchema) {
    try {
      return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(objectMapper.valueToTree(outputSchema));
    } catch (RuntimeException exception) {
      throw new McpContractException("MCP_CONTRACT_INVALID", exception);
    }
  }

  private static void validateIds(JsonNode node, Map<String, Set<String>> inboundIdAllowlist) {
    Map<String, Set<String>> allowlist = new HashMap<>();
    inboundIdAllowlist.forEach((field, values) -> allowlist.put(field, Set.copyOf(values)));
    validateIdsRecursively(node, allowlist);
  }

  private static void validateIdsRecursively(
      JsonNode node, Map<String, Set<String>> inboundIdAllowlist) {
    if (node.isObject()) {
      for (var property : node.properties()) {
        Set<String> allowed = inboundIdAllowlist.get(property.getKey());
        if (allowed != null) validateIdValue(property.getValue(), allowed);
        validateIdsRecursively(property.getValue(), inboundIdAllowlist);
      }
      return;
    }
    if (node.isArray()) node.forEach(child -> validateIdsRecursively(child, inboundIdAllowlist));
  }

  private static void validateIdValue(JsonNode value, Set<String> allowed) {
    Set<String> actual = new HashSet<>();
    if (value.isTextual()) {
      actual.add(value.asText());
    } else if (value.isArray()) {
      value.forEach(item -> actual.add(item.asText()));
    } else if (!value.isNull()) {
      throw new McpContractException("UNKNOWN_MCP_ID");
    }
    if (!allowed.containsAll(actual)) throw new McpContractException("UNKNOWN_MCP_ID");
  }

  private static McpContractException invalidContract() {
    return new McpContractException("MCP_CONTRACT_INVALID");
  }
}
