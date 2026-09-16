package com.timingjeju.api.domain.accountdeletion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.accountdeletion.service.AccountDeletionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class AccountDeletionFeatureFlagTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(AccountDeletionController.class)
          .withBean(AccountDeletionService.class, () -> mock(AccountDeletionService.class))
          .withBean(CurrentUserAccessor.class, () -> mock(CurrentUserAccessor.class));

  @Test
  void 기본값_false에서는_탈퇴_API를_등록하지_않는다() {
    runner.run(context -> assertThat(context).doesNotHaveBean(AccountDeletionController.class));
  }

  @Test
  void 단일_feature_flag_true에서만_탈퇴_API를_등록한다() {
    runner
        .withPropertyValues("app.account-deletion.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(AccountDeletionController.class));
  }
}
