package com.timingjeju.api.domain.accountdeletion.config;

import com.timingjeju.api.application.notification.PushNotificationWithdrawalBoundary;
import com.timingjeju.api.domain.accountdeletion.adapter.AccountDeletionSecretCleanupScheduler;
import com.timingjeju.api.domain.accountdeletion.adapter.UlidAccountDeletionRequestIdProvider;
import com.timingjeju.api.domain.accountdeletion.port.RecentAuthSessionGateway;
import com.timingjeju.api.domain.accountdeletion.repository.AccountDeletionRepository;
import com.timingjeju.api.domain.accountdeletion.security.AccountDeletionKeyProvider;
import com.timingjeju.api.domain.accountdeletion.security.AesGcmVersionedKeyRing;
import com.timingjeju.api.domain.accountdeletion.security.FileAccountDeletionKeyProvider;
import com.timingjeju.api.domain.accountdeletion.security.LocalAccountDeletionKeyProvider;
import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import com.timingjeju.api.domain.accountdeletion.service.AccountDeletionService;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.account-deletion", name = "enabled", havingValue = "true")
public class AccountDeletionConfiguration {
  @Bean
  VersionedAeadKeyRing accountDeletionKeyRing(
      @Value("${app.account-deletion.encryption.key-descriptor-file:}") String descriptorFile,
      @Value("${app.account-deletion.encryption.local-active-version:}") String localVersion,
      @Value("${app.account-deletion.encryption.local-active-key:}") String localKey) {
    AccountDeletionKeyProvider provider;
    if (!descriptorFile.isBlank()) {
      provider = new FileAccountDeletionKeyProvider(Path.of(descriptorFile));
    } else if (!localVersion.isBlank() && !localKey.isBlank()) {
      provider = new LocalAccountDeletionKeyProvider(localVersion, localKey);
    } else {
      throw new IllegalStateException(
          "APP_ACCOUNT_DELETION_STATUS_TOKEN_ENCRYPTION_KEY key descriptor가 필요합니다.");
    }
    var keySet = provider.load();
    return new AesGcmVersionedKeyRing(keySet.activeVersion(), keySet.keys(), new SecureRandom());
  }

  @Bean
  AccountDeletionService accountDeletionService(
      AccountDeletionRepository repository,
      RecentAuthSessionGateway sessions,
      VersionedAeadKeyRing keyRing,
      PushNotificationWithdrawalBoundary pushWithdrawal,
      @Value("${app.account-deletion.recent-auth-max-age:PT15M}") Duration recentAuthMaxAge,
      @Value("${app.account-deletion.status-token-ttl:PT24H}") Duration tokenTtl) {
    validateDurations(recentAuthMaxAge, tokenTtl);
    SecureRandom random = new SecureRandom();
    return new AccountDeletionService(
        repository,
        sessions,
        keyRing,
        pushWithdrawal,
        () -> {
          byte[] token = new byte[32];
          random.nextBytes(token);
          return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        },
        new UlidAccountDeletionRequestIdProvider(Clock.systemUTC(), random),
        Clock.systemUTC(),
        recentAuthMaxAge,
        tokenTtl);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.account-deletion.secret-cleanup",
      name = "enabled",
      havingValue = "true")
  AccountDeletionSecretCleanupScheduler accountDeletionSecretCleanupScheduler(
      AccountDeletionRepository repository,
      @Value("${app.account-deletion.secret-cleanup.terminal-retention:PT24H}")
          Duration terminalRetention,
      @Value("${app.account-deletion.secret-cleanup.batch-size:100}") int batchSize) {
    if (terminalRetention.isZero()
        || terminalRetention.isNegative()
        || terminalRetention.compareTo(Duration.ofDays(7)) > 0) {
      throw new IllegalArgumentException("terminal retention은 (0, 7일]이어야 합니다.");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("secret cleanup batch size는 1..1000이어야 합니다.");
    }
    return new AccountDeletionSecretCleanupScheduler(
        repository, Clock.systemUTC(), terminalRetention, batchSize);
  }

  static void validateDurations(Duration recentAuthMaxAge, Duration tokenTtl) {
    if (recentAuthMaxAge == null
        || recentAuthMaxAge.isZero()
        || recentAuthMaxAge.isNegative()
        || recentAuthMaxAge.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("recent auth max age는 (0, 24시간]이어야 합니다.");
    }
    if (tokenTtl == null
        || tokenTtl.isZero()
        || tokenTtl.isNegative()
        || tokenTtl.compareTo(Duration.ofDays(7)) > 0) {
      throw new IllegalArgumentException("status token TTL은 (0, 7일]이어야 합니다.");
    }
  }
}
