package com.timingjeju.api.domain.accountdeletion.service;

import com.timingjeju.api.application.notification.PushNotificationWithdrawalBoundary;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionException;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionReceipt;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionRecord;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatus;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatusView;
import com.timingjeju.api.domain.accountdeletion.port.AccountDeletionRequestIdProvider;
import com.timingjeju.api.domain.accountdeletion.port.AccountDeletionStatusTokenProvider;
import com.timingjeju.api.domain.accountdeletion.port.RecentAuthSessionGateway;
import com.timingjeju.api.domain.accountdeletion.repository.AccountDeletionRepository;
import com.timingjeju.api.domain.accountdeletion.security.EncryptedSecret;
import com.timingjeju.api.domain.accountdeletion.security.SecretDecryptionException;
import com.timingjeju.api.domain.accountdeletion.security.SecretPurpose;
import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

public class AccountDeletionService {
  public static final String CONFIRMATION = "DELETE_MY_ACCOUNT";
  private static final String POLL_PREFIX = "/api/v1/account-deletion-requests/";
  private final AccountDeletionRepository repository;
  private final RecentAuthSessionGateway sessions;
  private final VersionedAeadKeyRing keyRing;
  private final PushNotificationWithdrawalBoundary pushWithdrawal;
  private final AccountDeletionStatusTokenProvider tokens;
  private final AccountDeletionRequestIdProvider ids;
  private final Clock clock;
  private final Duration recentAuthMaxAge;
  private final Duration tokenTtl;

  public AccountDeletionService(
      AccountDeletionRepository repository,
      RecentAuthSessionGateway sessions,
      VersionedAeadKeyRing keyRing,
      PushNotificationWithdrawalBoundary pushWithdrawal,
      AccountDeletionStatusTokenProvider tokens,
      AccountDeletionRequestIdProvider ids,
      Clock clock,
      Duration recentAuthMaxAge,
      Duration tokenTtl) {
    this.repository = repository;
    this.sessions = sessions;
    this.keyRing = keyRing;
    this.pushWithdrawal = pushWithdrawal;
    this.tokens = tokens;
    this.ids = ids;
    this.clock = clock;
    this.recentAuthMaxAge = recentAuthMaxAge;
    this.tokenTtl = tokenTtl;
  }

  @Transactional
  public AccountDeletionReceipt request(
      CurrentUser user, String idempotencyKey, String confirmation) {
    validateIdempotencyKey(idempotencyKey);
    Instant now = clock.instant();
    if (user.sessionId() == null
        || !sessions.isRecent(user.userId(), user.sessionId(), now.minus(recentAuthMaxAge))) {
      throw problem("RECENT_REAUTHENTICATION_REQUIRED");
    }
    byte[] idempotencyHash = hash(idempotencyKey);
    byte[] requestHash = hash(confirmation == null ? "<null>" : confirmation);
    var previous = repository.findForReplay(user.userId(), idempotencyHash);
    if (previous.isPresent()) return replay(previous.get(), requestHash, now);
    if (!CONFIRMATION.equals(confirmation)) throw problem("INVALID_PROFILE_LEGAL_REQUEST");

    String requestId = ids.generate();
    String token = tokens.generate();
    byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    EncryptedSecret protectedToken =
        keyRing.encrypt(requestId, SecretPurpose.STATUS_TOKEN, tokenBytes);
    EncryptedSecret protectedSubject =
        keyRing.encrypt(
            requestId,
            SecretPurpose.AUTH_SUBJECT,
            user.userId().toString().getBytes(StandardCharsets.US_ASCII));
    AccountDeletionRecord created =
        new AccountDeletionRecord(
            requestId,
            user.userId(),
            hash(user.userId().toString().toLowerCase(java.util.Locale.ROOT)),
            idempotencyHash,
            requestHash,
            hash(tokenBytes),
            protectedToken.ciphertext(),
            protectedToken.keyVersion(),
            now.plus(tokenTtl),
            protectedSubject.ciphertext(),
            protectedSubject.keyVersion(),
            AccountDeletionStatus.QUEUED,
            "queued",
            null,
            now,
            null);
    repository.insert(created);
    pushWithdrawal.onWithdrawalRequested(user.userId(), now);
    return receipt(created, token);
  }

  @Transactional(readOnly = true)
  public AccountDeletionStatusView status(String requestId, String statusToken) {
    if (!canonicalRequestId(requestId) || !canonicalStatusToken(statusToken)) {
      throw problem("INVALID_DELETION_STATUS_TOKEN");
    }
    byte[] presentedHash = hash(statusToken.getBytes(StandardCharsets.US_ASCII));
    var found = repository.findByTokenHash(presentedHash);
    byte[] expectedHash =
        found.map(AccountDeletionRecord::statusTokenHash).orElseGet(() -> new byte[32]);
    boolean verified = MessageDigest.isEqual(expectedHash, presentedHash);
    if (!verified || found.isEmpty()) throw problem("INVALID_DELETION_STATUS_TOKEN");
    AccountDeletionRecord record = found.get();
    if (!record.id().equals(requestId)) throw problem("DELETION_STATUS_FORBIDDEN");
    if (!clock.instant().isBefore(record.statusTokenExpiresAt())) {
      throw problem("DELETION_STATUS_TOKEN_EXPIRED");
    }
    return new AccountDeletionStatusView(
        record.id(),
        record.status(),
        record.currentStep(),
        record.nextRetryAt(),
        record.completedAt());
  }

  private static boolean canonicalRequestId(String value) {
    return value != null && value.matches("^[0-9A-HJKMNP-TV-Z]{26}$");
  }

  private static boolean canonicalStatusToken(String value) {
    return value != null && value.matches("^[A-Za-z0-9_-]{43}$");
  }

  private AccountDeletionReceipt replay(
      AccountDeletionRecord record, byte[] requestHash, Instant now) {
    if (!MessageDigest.isEqual(record.requestHash(), requestHash)) {
      throw problem("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }
    if (!now.isBefore(record.statusTokenExpiresAt())) {
      throw problem("DELETION_STATUS_TOKEN_EXPIRED");
    }
    if (record.statusTokenCiphertext() == null || record.statusTokenKeyVersion() == null) {
      throw problem("ACCOUNT_DELETION_SECRET_UNAVAILABLE");
    }
    try {
      byte[] plaintext =
          keyRing.decrypt(
              record.id(),
              SecretPurpose.STATUS_TOKEN,
              new EncryptedSecret(record.statusTokenCiphertext(), record.statusTokenKeyVersion()));
      return receipt(record, new String(plaintext, StandardCharsets.UTF_8));
    } catch (SecretDecryptionException exception) {
      throw problem("ACCOUNT_DELETION_SECRET_UNAVAILABLE");
    }
  }

  private static AccountDeletionReceipt receipt(AccountDeletionRecord record, String token) {
    return new AccountDeletionReceipt(
        record.id(),
        AccountDeletionStatus.QUEUED,
        token,
        record.requestedAt(),
        record.statusTokenExpiresAt(),
        POLL_PREFIX + record.id());
  }

  private static void validateIdempotencyKey(String value) {
    if (value == null || value.isEmpty() || value.length() > 128) {
      throw problem("IDEMPOTENCY_KEY_INVALID");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20 || character > 0x7e) throw problem("IDEMPOTENCY_KEY_INVALID");
    }
  }

  private static byte[] hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] hash(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static AccountDeletionException problem(String code) {
    return new AccountDeletionException(code);
  }
}
