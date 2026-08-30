package com.timingjeju.api.global.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.notification.RegistrationTokenProtectionFailure;
import java.nio.charset.StandardCharsets;
import java.security.ProviderException;
import java.security.SecureRandom;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AesGcmRegistrationTokenProtectorTest {

  private static final String TOKEN = "__REDACTED_REGISTRATION_TOKEN__";

  @Test
  void 같은_token은_같은_SHA256_fingerprint와_매번_다른_ciphertext를_만든다() {
    var protector = protector();

    var first = protector.protect(TOKEN);
    var second = protector.protect(TOKEN);

    assertThat(first.fingerprint()).isEqualTo(second.fingerprint()).hasSize(32);
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext()).doesNotContain(TOKEN);
    assertThat(protector.reveal(first.ciphertext())).isEqualTo(TOKEN);
    assertThat(protector.reveal(second.ciphertext())).isEqualTo(TOKEN);
  }

  @Test
  void 변조된_ciphertext의_실패는_token이나_crypto원인을_노출하지_않는다() {
    var protector = protector();
    String ciphertext = protector.protect(TOKEN).ciphertext();
    String corrupted = ciphertext.substring(0, ciphertext.length() - 2) + "AA";

    Throwable failure = catchThrowable(() -> protector.reveal(corrupted));

    assertThat(failure).isInstanceOf(RegistrationTokenProtectionFailure.class);
    assertThat(failure).hasMessage(null).hasNoCause();
    assertThat(failure.toString()).doesNotContain(TOKEN).doesNotContain("AEADBadTag");
  }

  @Test
  void crypto_provider실패도_typed경계밖으로_cause_message_token을_노출하지_않는다() {
    SecureRandom failingRandom = mock(SecureRandom.class);
    doThrow(new ProviderException("provider leaked"))
        .when(failingRandom)
        .nextBytes(any(byte[].class));
    var protector =
        new AesGcmRegistrationTokenProtector(
            new SecretKeySpec(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES"),
            failingRandom);

    Throwable failure = catchThrowable(() -> protector.protect(TOKEN));

    assertThat(failure).isInstanceOf(RegistrationTokenProtectionFailure.class);
    assertThat(failure).hasMessage(null).hasNoCause();
    assertThat(failure.toString()).doesNotContain(TOKEN).doesNotContain("provider leaked");
  }

  @Test
  void 보호결과의_toString은_ciphertext와_fingerprint를_redact한다() {
    var protectedToken = protector().protect(TOKEN);

    assertThat(protectedToken.toString())
        .contains("<redacted>")
        .doesNotContain(protectedToken.ciphertext())
        .doesNotContain(java.util.HexFormat.of().formatHex(protectedToken.fingerprint()));
  }

  @Test
  void ASCII_4096byte_token의_base64url_envelope는_DB_5500자_상한을_넘지_않는다() {
    var protectedToken = protector().protect("A".repeat(4096));

    assertThat(protectedToken.ciphertext()).hasSizeLessThanOrEqualTo(5500);
    assertThat(protector().protect("A".repeat(4097)).ciphertext()).hasSizeGreaterThan(5500);
  }

  private static AesGcmRegistrationTokenProtector protector() {
    return new AesGcmRegistrationTokenProtector(
        new SecretKeySpec(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES"),
        new SecureRandom());
  }
}
