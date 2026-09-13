package com.timingjeju.api.domain.accountdeletion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AesGcmVersionedKeyRingTest {
  @Test
  void active_256bit_key로_용도별_AAD_암호화하고_과거_version을_복호화한다() {
    var old = new SecretKeySpec(new byte[32], "AES");
    byte[] activeBytes = new byte[32];
    activeBytes[0] = 1;
    var active = new SecretKeySpec(activeBytes, "AES");
    var ring =
        new AesGcmVersionedKeyRing("v2", Map.of("v1", old, "v2", active), new SecureRandom());

    EncryptedSecret protectedToken = ring.encrypt(SecretPurpose.STATUS_TOKEN, "opaque".getBytes());

    assertThat(protectedToken.keyVersion()).isEqualTo("v2");
    assertThat(ring.decrypt(SecretPurpose.STATUS_TOKEN, protectedToken))
        .isEqualTo("opaque".getBytes());
    assertThatThrownBy(() -> ring.decrypt(SecretPurpose.AUTH_SUBJECT, protectedToken))
        .isInstanceOf(SecretDecryptionException.class);
    assertThatThrownBy(
            () ->
                ring.decrypt(
                    SecretPurpose.STATUS_TOKEN,
                    new EncryptedSecret(protectedToken.ciphertext(), "missing")))
        .isInstanceOf(SecretDecryptionException.class);
  }

  @Test
  void key가_256bit보다_짧거나_active_version이_없으면_거부한다() {
    assertThatThrownBy(
            () ->
                new AesGcmVersionedKeyRing(
                    "v1", Map.of("v1", new SecretKeySpec(new byte[16], "AES")), new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AesGcmVersionedKeyRing(
                    "v2", Map.of("v1", new SecretKeySpec(new byte[32], "AES")), new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
