package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ReloadingMcpSigningKeyProviderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void atomic_descriptor_교체로_old_new_key를_재시작없이_rotation한다() throws Exception {
    Path oldKey = writeKey("old.pem");
    Path newKey = writeKey("new.pem");
    Path descriptor = temporaryDirectory.resolve("active-key.json");
    writeDescriptor(descriptor, "old-kid", oldKey);
    ReloadingMcpSigningKeyProvider provider =
        new ReloadingMcpSigningKeyProvider(descriptor, new ObjectMapper());

    McpSigningKey old = provider.current();
    writeDescriptor(descriptor, "new-kid", newKey);
    McpSigningKey current = provider.current();

    assertThat(old.keyId()).isEqualTo("old-kid");
    assertThat(current.keyId()).isEqualTo("new-kid");
    assertThat(current.privateKey()).isNotEqualTo(old.privateKey());

    Files.writeString(descriptor, "{\"kid\":\"unknown\"}", StandardCharsets.UTF_8);
    assertThatThrownBy(provider::current)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MCP signing key descriptor를 읽을 수 없습니다.");
  }

  private Path writeKey(String name) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String encoded =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    Path path = temporaryDirectory.resolve(name);
    String keyLabel = "PRIVATE" + " KEY";
    Files.writeString(
        path,
        "-----BEGIN " + keyLabel + "-----\n" + encoded + "\n-----END " + keyLabel + "-----\n",
        StandardCharsets.US_ASCII);
    return path;
  }

  private static void writeDescriptor(Path descriptor, String kid, Path privateKey)
      throws Exception {
    Files.writeString(
        descriptor,
        "{\"kid\":\"" + kid + "\",\"privateKeyFile\":\"" + privateKey + "\"}",
        StandardCharsets.UTF_8);
  }
}
