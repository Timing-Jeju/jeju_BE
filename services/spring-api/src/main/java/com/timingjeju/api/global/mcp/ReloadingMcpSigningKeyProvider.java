package com.timingjeju.api.global.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ReloadingMcpSigningKeyProvider implements McpSigningKeyProvider {
  private static final long MAX_DESCRIPTOR_BYTES = 4L * 1024L;
  private final Path descriptorFile;
  private final ObjectMapper objectMapper;

  public ReloadingMcpSigningKeyProvider(Path descriptorFile, ObjectMapper objectMapper) {
    this.descriptorFile = Objects.requireNonNull(descriptorFile, "descriptorFile은 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    if (!descriptorFile.isAbsolute()) {
      throw new IllegalArgumentException("MCP signing key descriptor 경로는 절대 경로여야 합니다.");
    }
  }

  @Override
  public McpSigningKey current() {
    try {
      if (!Files.isRegularFile(descriptorFile)
          || Files.size(descriptorFile) > MAX_DESCRIPTOR_BYTES) {
        throw new IllegalStateException();
      }
      JsonNode descriptor = objectMapper.readTree(descriptorFile.toFile());
      if (!descriptor.isObject()
          || descriptor.size() != 2
          || !descriptor.has("kid")
          || !descriptor.has("privateKeyFile")) {
        throw new IllegalStateException();
      }
      String keyId = descriptor.path("kid").asText();
      String keyFileValue = descriptor.path("privateKeyFile").asText();
      Path keyFile = Path.of(keyFileValue);
      if (!keyFile.isAbsolute()) throw new IllegalStateException();
      return new McpSigningKey(keyId, McpPemPrivateKeyLoader.load(keyFile));
    } catch (Exception exception) {
      throw new IllegalStateException("MCP signing key descriptor를 읽을 수 없습니다.");
    }
  }
}
