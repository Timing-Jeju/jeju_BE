package com.timingjeju.api.domain.accountdeletion.worker.config;

import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorkRepository;
import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorker;
import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorkerCommand;
import com.timingjeju.api.domain.accountdeletion.worker.AccountRequestDenier;
import com.timingjeju.api.domain.accountdeletion.worker.AppOwnedDataErasure;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionWorkerPolicy;
import com.timingjeju.api.domain.accountdeletion.worker.EncryptedSubjectResolver;
import com.timingjeju.api.domain.accountdeletion.worker.GlobalSessionRevoker;
import com.timingjeju.api.domain.accountdeletion.worker.ProfileImageDeletion;
import com.timingjeju.api.domain.accountdeletion.worker.SupabaseAuthAdminDeletion;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.AccountDeletionWorkerScheduler;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAccountDeletionWorkRepository;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.KeyRingEncryptedSubjectResolver;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.ProfileImageDeletionAdapter;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.ProfileImageStorageGateway;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAuthAdminDeletionAdapter;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.SupabaseAuthAdminGateway;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "app.account-deletion.worker",
    name = "enabled",
    havingValue = "true")
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
  GlobalSessionRevoker globalSessionRevoker() {
    return subject -> {
      throw disabled();
    };
  }

  @Bean
  @ConditionalOnMissingBean(AccountRequestDenier.class)
  AccountRequestDenier accountRequestDenier() {
    return subject -> {
      throw disabled();
    };
  }

  @Bean
  @ConditionalOnMissingBean(AppOwnedDataErasure.class)
  AppOwnedDataErasure appOwnedDataErasure() {
    return subject -> {
      throw disabled();
    };
  }

  @Bean
  @ConditionalOnMissingBean(ProfileImageStorageGateway.class)
  ProfileImageStorageGateway disabledProfileImageStorageGateway() {
    return prefix -> {
      throw disabled();
    };
  }

  @Bean
  @ConditionalOnMissingBean(SupabaseAuthAdminGateway.class)
  SupabaseAuthAdminGateway disabledSupabaseAuthAdminGateway() {
    return subject -> {
      throw disabled();
    };
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

  private static DeletionOperationException disabled() {
    return DeletionOperationException.terminal("ACCOUNT_DELETION_EXTERNAL_OPERATIONS_DISABLED");
  }
}
