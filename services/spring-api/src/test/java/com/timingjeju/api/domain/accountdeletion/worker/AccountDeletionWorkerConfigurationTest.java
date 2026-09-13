package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.domain.accountdeletion.security.VersionedAeadKeyRing;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.AccountDeletionWorkerScheduler;
import com.timingjeju.api.domain.accountdeletion.worker.config.AccountDeletionWorkerConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("unit")
class AccountDeletionWorkerConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(AccountDeletionWorkerConfiguration.class)
          .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
          .withBean(VersionedAeadKeyRing.class, () -> mock(VersionedAeadKeyRing.class));

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
  void enabled는_명시적_worker_id와_fail_closed_gateway로만_구성된다() {
    runner
        .withPropertyValues(
            "app.account-deletion.worker.enabled=true", "app.account-deletion.worker.id=worker-106")
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
        .withPropertyValues("app.account-deletion.worker.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }
}
