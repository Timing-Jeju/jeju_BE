package com.timingjeju.api.global.mcp;

import java.security.interfaces.RSAPrivateKey;
import java.util.Objects;

public record McpSigningKey(String keyId, RSAPrivateKey privateKey) {
  public McpSigningKey {
    if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,128}")) {
      throw new IllegalArgumentException("MCP signing key id가 올바르지 않습니다.");
    }
    Objects.requireNonNull(privateKey, "privateKey는 필수입니다.");
    if (privateKey.getModulus().bitLength() < 2048) {
      throw new IllegalArgumentException("RSA key는 2048 bit 이상이어야 합니다.");
    }
  }
}
