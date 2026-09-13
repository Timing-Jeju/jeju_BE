package com.timingjeju.api.domain.accountdeletion.config;

import com.timingjeju.api.application.notification.PushNotificationWithdrawalBoundary;
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

@Configuration(proxyBeanMethods = false)
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
}
