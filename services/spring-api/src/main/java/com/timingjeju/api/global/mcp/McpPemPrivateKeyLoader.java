package com.timingjeju.api.global.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

final class McpPemPrivateKeyLoader {
  private static final long MAX_KEY_BYTES = 64L * 1024L;

  private McpPemPrivateKeyLoader() {}

  static RSAPrivateKey load(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      throw new IllegalStateException("MCP private key file을 읽을 수 없습니다.");
    }
    try {
      if (Files.size(path) > MAX_KEY_BYTES) {
        throw new IllegalStateException("MCP private key file이 너무 큽니다.");
      }
      String pem = Files.readString(path, StandardCharsets.US_ASCII);
      String encoded =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      byte[] keyBytes = Base64.getDecoder().decode(encoded);
      var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
      if (!(key instanceof RSAPrivateKey rsaPrivateKey)) {
        throw new IllegalStateException("MCP private key는 RSA여야 합니다.");
      }
      return rsaPrivateKey;
    } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException("MCP private key file을 읽을 수 없습니다.", exception);
    }
  }
}
