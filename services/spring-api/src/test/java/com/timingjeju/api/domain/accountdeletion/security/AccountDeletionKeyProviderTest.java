package com.timingjeju.api.domain.accountdeletion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class AccountDeletionKeyProviderTest {
  @TempDir Path directory;

  @Test
  void descriptor는_active와_과거_decrypt_version의_256bit_key를_로드한다() throws Exception {
    String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] active = new byte[32];
    active[0] = 1;
    String activeKey = Base64.getEncoder().encodeToString(active);
    Path descriptor = directory.resolve("account-deletion-key.properties");
    Files.writeString(
        descriptor, "active-version=v2\nkey.v1=" + oldKey + "\nkey.v2=" + activeKey + "\n");

    AccountDeletionKeySet keys = new FileAccountDeletionKeyProvider(descriptor).load();

    assertThat(keys.activeVersion()).isEqualTo("v2");
    assertThat(keys.keys()).containsOnlyKeys("v1", "v2");
    assertThat(keys.keys().get("v2").getEncoded()).hasSize(32);
  }

  @Test
  void 없는_descriptor와_짧은_key는_비밀값없이_fail_fast한다() throws Exception {
    assertThatThrownBy(
            () -> new FileAccountDeletionKeyProvider(directory.resolve("missing")).load())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("missing");
    Path descriptor = directory.resolve("invalid.properties");
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    Files.writeString(descriptor, "active-version=v1\nkey.v1=" + shortKey + "\n");
    assertThatThrownBy(() -> new FileAccountDeletionKeyProvider(descriptor).load())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(shortKey);
  }
}
