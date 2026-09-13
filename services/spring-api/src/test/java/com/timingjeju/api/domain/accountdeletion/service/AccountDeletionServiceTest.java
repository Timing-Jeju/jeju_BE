package com.timingjeju.api.domain.accountdeletion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionException;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionRecord;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatus;
import com.timingjeju.api.domain.accountdeletion.repository.AccountDeletionRepository;
import com.timingjeju.api.domain.accountdeletion.security.EncryptedSecret;
import com.timingjeju.api.domain.accountdeletion.security.SecretPurpose;
import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccountDeletionServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
  private static final UUID USER = UUID.fromString("61000000-0000-0000-0000-000000000001");
  private static final UUID SESSION = UUID.fromString("61000000-0000-0000-0000-000000000002");
  private static final String REQUEST = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
  private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void recent_session의_첫_요청은_token을_한번만_생성하고_queued를_반환한다() {
    Fixture fixture = new Fixture(true);

    var result = fixture.service.request(user(SESSION), "delete-1", "DELETE_MY_ACCOUNT");

    assertThat(result.deletionRequestId()).isEqualTo(REQUEST);
    assertThat(result.status()).isEqualTo(AccountDeletionStatus.QUEUED);
    assertThat(result.statusToken()).isEqualTo(TOKEN);
    assertThat(fixture.generated).isEqualTo(1);
    assertThat(fixture.withdrawals).isEqualTo(1);
    assertThat(fixture.repository.saved.authSubjectCiphertext()).doesNotContain(USER.toString());
    assertThat(fixture.repository.saved.statusTokenCiphertext()).doesNotContain(TOKEN);
  }

  @Test
  void 같은_사용자_key_body_replay는_만료전_같은_request와_token을_반환한다() {
    Fixture fixture = new Fixture(true);
    var first = fixture.service.request(user(SESSION), "printable key:1", "DELETE_MY_ACCOUNT");

    var replay = fixture.service.request(user(SESSION), "printable key:1", "DELETE_MY_ACCOUNT");

    assertThat(replay).isEqualTo(first);
    assertThat(fixture.generated).isEqualTo(1);
    assertThat(fixture.withdrawals).isEqualTo(1);
  }

  @Test
  void 같은_key의_payload_conflict와_만료_replay는_새_token없이_거부한다() {
    Fixture fixture = new Fixture(true);
    fixture.service.request(user(SESSION), "delete-1", "DELETE_MY_ACCOUNT");

    assertThatThrownBy(() -> fixture.service.request(user(SESSION), "delete-1", "WRONG"))
        .isInstanceOf(AccountDeletionException.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT");
    fixture.repository.saved = fixture.repository.saved.withTokenExpiresAt(NOW.minusSeconds(1));
    assertThatThrownBy(
            () -> fixture.service.request(user(SESSION), "delete-1", "DELETE_MY_ACCOUNT"))
        .isInstanceOf(AccountDeletionException.class)
        .extracting("code")
        .isEqualTo("DELETION_STATUS_TOKEN_EXPIRED");
    assertThat(fixture.generated).isEqualTo(1);
  }

  @Test
  void session_id가_없거나_최근_session이_아니면_요청을_저장하지_않는다() {
    Fixture old = new Fixture(false);
    assertThatThrownBy(() -> old.service.request(user(SESSION), "delete-1", "DELETE_MY_ACCOUNT"))
        .isInstanceOf(AccountDeletionException.class)
        .extracting("code")
        .isEqualTo("RECENT_REAUTHENTICATION_REQUIRED");
    assertThatThrownBy(() -> old.service.request(user(null), "delete-2", "DELETE_MY_ACCOUNT"))
        .isInstanceOf(AccountDeletionException.class)
        .extracting("code")
        .isEqualTo("RECENT_REAUTHENTICATION_REQUIRED");
    assertThat(old.repository.saved).isNull();
  }

  @Test
  void status_token은_만료전_반복조회되고_다섯_상태와_cancelled_terminal을_지원한다() {
    for (AccountDeletionStatus status : AccountDeletionStatus.values()) {
      Fixture fixture = new Fixture(true);
      fixture.service.request(user(SESSION), "delete-1", "DELETE_MY_ACCOUNT");
      fixture.repository.saved = fixture.repository.saved.withStatus(status);

      assertThat(fixture.service.status(REQUEST, TOKEN).status()).isEqualTo(status);
      assertThat(fixture.service.status(REQUEST, TOKEN).status()).isEqualTo(status);
    }
    assertThat(AccountDeletionStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(AccountDeletionStatus.RUNNING.isTerminal()).isFalse();
  }

  @Test
  void 다른_token은_constant_time_hash검증으로_거부하고_응답에_PII를_포함하지_않는다() {
    Fixture fixture = new Fixture(true);
    fixture.service.request(user(SESSION), "delete-1", "DELETE_MY_ACCOUNT");

    assertThatThrownBy(
            () -> fixture.service.status(REQUEST, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"))
        .isInstanceOf(AccountDeletionException.class)
        .extracting("code")
        .isEqualTo("INVALID_DELETION_STATUS_TOKEN");
    assertThat(fixture.service.status(REQUEST, TOKEN).toString())
        .doesNotContain(USER.toString())
        .doesNotContain(TOKEN);
  }

  @Test
  void 검증된_token과_다른_ULID는_존재여부를_노출하지_않고_403이다() {
    Fixture fixture = new Fixture(true);
    fixture.service.request(user(SESSION), "delete-1", "DELETE_MY_ACCOUNT");

    assertThatThrownBy(() -> fixture.service.status("01ARZ3NDEKTSV4RRFFQ69G5FAW", TOKEN))
        .isInstanceOf(AccountDeletionException.class)
        .extracting("code")
        .isEqualTo("DELETION_STATUS_FORBIDDEN");
  }

  @Test
  void Idempotency_Key는_1자와_128자_printable_ASCII만_허용한다() {
    Fixture one = new Fixture(true);
    assertThat(one.service.request(user(SESSION), "!", "DELETE_MY_ACCOUNT").status())
        .isEqualTo(AccountDeletionStatus.QUEUED);
    Fixture max = new Fixture(true);
    assertThat(max.service.request(user(SESSION), "~".repeat(128), "DELETE_MY_ACCOUNT").status())
        .isEqualTo(AccountDeletionStatus.QUEUED);
    for (String invalid : java.util.List.of("", "a".repeat(129), "line\nbreak", "한글")) {
      Fixture fixture = new Fixture(true);
      assertThatThrownBy(() -> fixture.service.request(user(SESSION), invalid, "DELETE_MY_ACCOUNT"))
          .isInstanceOf(AccountDeletionException.class)
          .extracting("code")
          .isEqualTo("IDEMPOTENCY_KEY_INVALID");
      assertThat(fixture.repository.saved).isNull();
    }
  }

  private static CurrentUser user(UUID session) {
    return new CurrentUser(USER, AuthenticatedRole.AUTHENTICATED, session);
  }

  private static final class Fixture {
    final InMemoryRepository repository = new InMemoryRepository();
    final VersionedAeadKeyRing keys = new FakeKeyRing();
    int generated;
    int withdrawals;
    final AccountDeletionService service;

    Fixture(boolean recent) {
      service =
          new AccountDeletionService(
              repository,
              (userId, sessionId, threshold) -> recent,
              keys,
              (userId, requestedAt) -> withdrawals++,
              () -> {
                generated++;
                return TOKEN;
              },
              () -> REQUEST,
              Clock.fixed(NOW, ZoneOffset.UTC),
              Duration.ofMinutes(15),
              Duration.ofHours(24));
    }
  }

  private static final class InMemoryRepository implements AccountDeletionRepository {
    AccountDeletionRecord saved;

    @Override
    public Optional<AccountDeletionRecord> findForReplay(UUID profileId, byte[] idempotencyHash) {
      return saved != null
              && saved.userProfileId().equals(profileId)
              && Arrays.equals(saved.idempotencyHash(), idempotencyHash)
          ? Optional.of(saved)
          : Optional.empty();
    }

    @Override
    public void insert(AccountDeletionRecord record) {
      saved = record;
    }

    @Override
    public Optional<AccountDeletionRecord> findByTokenHash(byte[] tokenHash) {
      return saved != null && Arrays.equals(saved.statusTokenHash(), tokenHash)
          ? Optional.of(saved)
          : Optional.empty();
    }
  }

  private static final class FakeKeyRing implements VersionedAeadKeyRing {
    private final Map<String, byte[]> values = new HashMap<>();
    private int sequence;

    @Override
    public EncryptedSecret encrypt(SecretPurpose purpose, byte[] plaintext) {
      String ciphertext = "ciphertext-" + (++sequence);
      values.put(purpose + ":" + ciphertext, plaintext.clone());
      return new EncryptedSecret(ciphertext, "v1");
    }

    @Override
    public byte[] decrypt(SecretPurpose purpose, EncryptedSecret secret) {
      byte[] value = values.get(purpose + ":" + secret.ciphertext());
      if (value == null) throw new IllegalStateException("ciphertext unavailable");
      return value.clone();
    }
  }
}
