package com.timingjeju.api.domain.accountdeletion.worker.config;

import com.timingjeju.api.application.security.AccountDeletionPendingAccess;
import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorkRepository;
import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorker;
import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorkerCommand;
import com.timingjeju.api.domain.accountdeletion.worker.AccountRequestDenier;
import com.timingjeju.api.domain.accountdeletion.worker.AppOwnedDataErasure;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionWorkerPolicy;
import com.timingjeju.api.domain.accountdeletion.worker.EncryptedSubjectResolver;
import com.timingjeju.api.domain.accountdeletion.worker.GlobalSessionRevoker;
import com.timingjeju.api.domain.accountdeletion.worker.ProfileImageDeletion;
import com.timingjeju.api.domain.accountdeletion.worker.SupabaseAuthAdminDeletion;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.AccountDeletionWorkerScheduler;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAccountDeletionWorkRepository;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAppOwnedDataErasure;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcDeletionPendingVerifier;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdkSupabaseAdminHttpTransport;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.KeyRingEncryptedSubjectResolver;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.ProfileImageDeletionAdapter;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.ProfileImageStorageGateway;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAdminHttpTransport;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAdminSettings;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAuthAdminDeletionAdapter;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAuthAdminGateway;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAuthAdminHttpGateway;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseProfileImageDeletionHttpGateway;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.account-deletion", name = "enabled", havingValue = "true")
public class AccountDeletionWorkerConfiguration {

  @Bean
  @ConditionalOnMissingBean(AccountDeletionWorkRepository.class)
  AccountDeletionWorkRepository accountDeletionWorkRepository(NamedParameterJdbcTemplate jdbc) {
    return new JdbcAccountDeletionWorkRepository(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(EncryptedSubjectResolver.class)
  EncryptedSubjectResolver encryptedSubjectResolver(VersionedAeadKeyRing keyRing) {
    return new KeyRingEncryptedSubjectResolver(keyRing);
  }

  @Bean
  @ConditionalOnMissingBean(GlobalSessionRevoker.class)
  GlobalSessionRevoker globalSessionRevoker(AccountDeletionPendingAccess pendingAccess) {
    JdbcDeletionPendingVerifier verifier = new JdbcDeletionPendingVerifier(pendingAccess);
    return verifier::revokeAll;
  }

  @Bean
  @ConditionalOnMissingBean(AccountRequestDenier.class)
  AccountRequestDenier accountRequestDenier(AccountDeletionPendingAccess pendingAccess) {
    JdbcDeletionPendingVerifier verifier = new JdbcDeletionPendingVerifier(pendingAccess);
    return verifier::denyFurtherRequests;
  }

  @Bean
  @ConditionalOnMissingBean(AppOwnedDataErasure.class)
  AppOwnedDataErasure appOwnedDataErasure(
      NamedParameterJdbcTemplate jdbc, TransactionOperations transaction) {
    return new JdbcAppOwnedDataErasure(jdbc, transaction);
  }

  @Bean
  @ConditionalOnMissingBean(SupabaseAdminSettings.class)
  SupabaseAdminSettings supabaseAdminSettings(
      @Value("${app.account-deletion.worker.supabase-url:}") String rawUrl,
      @Value("${app.account-deletion.worker.service-role-key:}") String serviceRoleKey,
      @Value("${app.account-deletion.worker.connect-timeout:PT2S}") String connectTimeout,
      @Value("${app.account-deletion.worker.read-timeout:PT5S}") String readTimeout,
      @Value("${app.account-deletion.worker.storage-page-size:1000}") int storagePageSize,
      @Value("${app.account-deletion.worker.storage-maximum-pages:100}") int maximumPages) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new IllegalStateException("worker 활성화 시 Supabase URL은 필수입니다.");
    }
    return SupabaseAdminSettings.enabled(
        URI.create(rawUrl),
        serviceRoleKey,
        Duration.parse(connectTimeout),
        Duration.parse(readTimeout),
        storagePageSize,
        maximumPages);
  }

  @Bean
  @ConditionalOnMissingBean(SupabaseAdminHttpTransport.class)
  SupabaseAdminHttpTransport supabaseAdminHttpTransport(SupabaseAdminSettings settings) {
    return new JdkSupabaseAdminHttpTransport(settings);
  }

  @Bean
  @ConditionalOnMissingBean(ProfileImageStorageGateway.class)
  ProfileImageStorageGateway profileImageStorageGateway(
      SupabaseAdminSettings settings,
      ObjectMapper objectMapper,
      SupabaseAdminHttpTransport transport) {
    return new SupabaseProfileImageDeletionHttpGateway(settings, objectMapper, transport);
  }

  @Bean
  @ConditionalOnMissingBean(SupabaseAuthAdminGateway.class)
  SupabaseAuthAdminGateway supabaseAuthAdminGateway(
      SupabaseAdminSettings settings, SupabaseAdminHttpTransport transport) {
    return new SupabaseAuthAdminHttpGateway(settings, transport);
  }

  @Bean
  @ConditionalOnMissingBean(ProfileImageDeletion.class)
  ProfileImageDeletion profileImageDeletion(ProfileImageStorageGateway gateway) {
    return new ProfileImageDeletionAdapter(gateway);
  }

  @Bean
  @ConditionalOnMissingBean(SupabaseAuthAdminDeletion.class)
  SupabaseAuthAdminDeletion supabaseAuthAdminDeletion(SupabaseAuthAdminGateway gateway) {
    return new SupabaseAuthAdminDeletionAdapter(gateway);
  }

  @Bean
  @ConditionalOnMissingBean(AccountDeletionWorkerCommand.class)
  AccountDeletionWorkerCommand accountDeletionWorker(
      AccountDeletionWorkRepository repository,
      EncryptedSubjectResolver subjectResolver,
      GlobalSessionRevoker sessionRevoker,
      AccountRequestDenier requestDenier,
      ProfileImageDeletion profileImageDeletion,
      AppOwnedDataErasure appDataErasure,
      SupabaseAuthAdminDeletion authAdminDeletion,
      @Value("${app.account-deletion.worker.id:}") String workerId) {
    return new AccountDeletionWorker(
        workerId,
        repository,
        subjectResolver,
        sessionRevoker,
        requestDenier,
        profileImageDeletion,
        appDataErasure,
        authAdminDeletion,
        DeletionWorkerPolicy.defaults(),
        Clock.systemUTC(),
        () -> ThreadLocalRandom.current().nextDouble());
  }

  @Bean
  AccountDeletionWorkerScheduler accountDeletionWorkerScheduler(
      AccountDeletionWorkerCommand command) {
    return new AccountDeletionWorkerScheduler(command);
  }
}
