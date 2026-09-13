package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.accountdeletion.security.EncryptedSecret;
import com.timingjeju.api.domain.accountdeletion.security.SecretPurpose;
import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.DisabledAccountDeletionOperations;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.ExternalDeletionResult;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.KeyRingEncryptedSubjectResolver;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.ProfileImageDeletionAdapter;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAuthAdminDeletionAdapter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccountDeletionWorkerAdaptersTest {
  @Test
  void keyring_resolver는_61_auth_subject_contract로_JIT복호화하고_실패를_terminal로_분류한다() {
    VersionedAeadKeyRing success = keyRingReturning("subject-106");

    AuthSubject resolved =
        new KeyRingEncryptedSubjectResolver(success)
            .resolve("01K4V106000000000000000001", new EncryptedAuthSubject("cipher", "v1"));

    assertThat(resolved.value()).isEqualTo("subject-106");
    VersionedAeadKeyRing failure =
        new VersionedAeadKeyRing() {
          @Override
          public EncryptedSecret encrypt(SecretPurpose purpose, byte[] plaintext) {
            throw new UnsupportedOperationException();
          }

          @Override
          public byte[] decrypt(SecretPurpose purpose, EncryptedSecret secret) {
            throw new IllegalArgumentException("raw-ciphertext");
          }
        };
    assertThatThrownBy(
            () ->
                new KeyRingEncryptedSubjectResolver(failure)
                    .resolve(
                        "01K4V106000000000000000001", new EncryptedAuthSubject("cipher", "v1")))
        .isInstanceOf(DeletionOperationException.class)
        .hasMessage("SUBJECT_DECRYPTION_FAILED")
        .hasNoCause();
  }

  @Test
  void storage와_auth의_already_absent는_멱등_성공으로_정규화한다() {
    var storage = new ProfileImageDeletionAdapter(prefix -> ExternalDeletionResult.ALREADY_ABSENT);
    var auth =
        new SupabaseAuthAdminDeletionAdapter(subject -> ExternalDeletionResult.ALREADY_ABSENT);

    assertThatCode(() -> storage.deletePrefix("profile-images/subject-106"))
        .doesNotThrowAnyException();
    assertThatCode(() -> auth.deleteUser(AuthSubject.of("subject-106"))).doesNotThrowAnyException();
  }

  @Test
  void live_gateway가_주입되지_않은_기본_operation은_분류된_terminal로_fail_closed한다() {
    DisabledAccountDeletionOperations disabled = new DisabledAccountDeletionOperations();

    assertThatThrownBy(() -> disabled.revokeAll(AuthSubject.of("subject-106")))
        .isInstanceOf(DeletionOperationException.class)
        .hasMessage("ACCOUNT_DELETION_EXTERNAL_OPERATIONS_DISABLED")
        .hasNoCause();
  }

  private static VersionedAeadKeyRing keyRingReturning(String subject) {
    return new VersionedAeadKeyRing() {
      @Override
      public EncryptedSecret encrypt(SecretPurpose purpose, byte[] plaintext) {
        throw new UnsupportedOperationException();
      }

      @Override
      public byte[] decrypt(SecretPurpose purpose, EncryptedSecret secret) {
        assertThat(purpose).isEqualTo(SecretPurpose.AUTH_SUBJECT);
        assertThat(secret).isEqualTo(new EncryptedSecret("cipher", "v1"));
        return subject.getBytes(StandardCharsets.US_ASCII);
      }
    };
  }
}
