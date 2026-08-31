package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class McpPemPrivateKeyLoaderTest {

  @TempDir Path temporaryDirectory;

  @Test
  void mount된_PKCS8_RSA_private_key만_읽는다() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded)
            + "\n-----END PRIVATE KEY-----\n";
    Path keyFile = temporaryDirectory.resolve("service.pem");
    Files.writeString(keyFile, pem, StandardCharsets.US_ASCII);

    assertThat(McpPemPrivateKeyLoader.load(keyFile).getModulus().bitLength())
        .isGreaterThanOrEqualTo(2048);
  }

  @Test
  void 없거나_손상됐거나_과도하게_큰_key_file은_거부한다() throws Exception {
    Path malformed = temporaryDirectory.resolve("malformed.pem");
    Files.writeString(malformed, "not-a-pem", StandardCharsets.US_ASCII);
    Path oversized = temporaryDirectory.resolve("oversized.pem");
    Files.writeString(oversized, "x".repeat(65_537), StandardCharsets.US_ASCII);

    assertThatThrownBy(() -> McpPemPrivateKeyLoader.load(temporaryDirectory.resolve("missing")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MCP private key file을 읽을 수 없습니다.");
    assertThatThrownBy(() -> McpPemPrivateKeyLoader.load(malformed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MCP private key file을 읽을 수 없습니다.");
    assertThatThrownBy(() -> McpPemPrivateKeyLoader.load(oversized))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MCP private key file이 너무 큽니다.");
  }
}
