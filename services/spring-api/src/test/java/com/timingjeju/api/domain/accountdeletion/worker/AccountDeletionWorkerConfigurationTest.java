package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.security.AccountDeletionPendingAccess;
import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.AccountDeletionWorkerScheduler;
import com.timingjeju.api.domain.accountdeletion.worker.config.AccountDeletionWorkerConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class AccountDeletionWorkerConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(AccountDeletionWorkerConfiguration.class)
          .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
          .withBean(VersionedAeadKeyRing.class, () -> mock(VersionedAeadKeyRing.class))
          .withBean(TransactionOperations.class, () -> mock(TransactionOperations.class))
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(AccountDeletionPendingAccess.class, () -> userId -> true);

  @Test
  void 기본값은_worker와_scheduler를_생성하지_않는다() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(AccountDeletionWorkerCommand.class);
          assertThat(context).doesNotHaveBean(AccountDeletionWorkerScheduler.class);
        });
  }

  @Test
  void enabled는_명시적_worker_id와_Supabase_production_gateway로만_구성된다() {
    runner
        .withPropertyValues(
            "app.account-deletion.enabled=true",
            "app.account-deletion.worker.id=worker-106",
            "app.account-deletion.worker.supabase-url=https://project.supabase.co",
            "app.account-deletion.worker.service-role-key=placeholder-service-role")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AccountDeletionWorkerCommand.class);
              assertThat(context).hasSingleBean(AccountDeletionWorkerScheduler.class);
              assertThat(context).hasSingleBean(ProfileImageDeletion.class);
              assertThat(context).hasSingleBean(SupabaseAuthAdminDeletion.class);
            });
  }

  @Test
  void enabled인데_worker_id가_없으면_fail_fast한다() {
    runner
        .withPropertyValues(
            "app.account-deletion.enabled=true",
            "app.account-deletion.worker.supabase-url=https://project.supabase.co",
            "app.account-deletion.worker.service-role-key=placeholder-service-role")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void enabled인데_Supabase_secret이_없으면_fail_fast한다() {
    runner
        .withPropertyValues(
            "app.account-deletion.enabled=true",
            "app.account-deletion.worker.id=worker-106",
            "app.account-deletion.worker.supabase-url=https://project.supabase.co")
        .run(context -> assertThat(context).hasFailed());
  }
}
