package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccountDeletionWorkerRuntimeMigrationTest {
  @Test
  void additive_migration은_lease_fence_retry와_step_history를_추가하고_61_schema를_수정하지_않는다()
      throws Exception {
    String sql =
        Files.readString(
                Path.of(
                    "../../supabase/migrations/20260919010000_account_deletion_worker_runtime.sql"))
            .toLowerCase();

    assertThat(sql)
        .contains("alter table public.account_deletion_requests")
        .contains(
            "lease_owner",
            "lease_expires_at",
            "fencing_token",
            "attempt",
            "failure_code",
            "cancellation_requested")
        .contains("create table public.account_deletion_steps")
        .contains("references public.account_deletion_requests(id) on delete cascade")
        .contains("enable row level security")
        .contains("revoke all on table public.account_deletion_steps from authenticated")
        .doesNotContain("auth.users", "storage.objects");
  }

  @Test
  void retention_migration은_법적동의를_익명_보존하고_request_profile_FK_SET_NULL을_유지한다() throws Exception {
    String sql =
        Files.readString(
                Path.of(
                    "../../supabase/migrations/20260919020000_account_deletion_retention_contract.sql"))
            .toLowerCase();

    assertThat(sql)
        .contains("alter table public.user_consents", "alter column user_id drop not null")
        .contains("on delete set null")
        .contains("account_deletion_requests_user_profile_id_fkey")
        .doesNotContain("delete from storage.objects", "auth.users");
  }

  @Test
  void worker_fencing_migration은_첫_파괴_marker와_cancel을_상호배타로_고정한다() throws Exception {
    String sql =
        Files.readString(
                Path.of(
                    "../../supabase/migrations/20260919040000_account_deletion_worker_fencing.sql"))
            .toLowerCase();

    assertThat(sql)
        .contains("destructive_started_at timestamptz")
        .contains("destructive_started_at is null or cancellation_requested = false")
        .contains("guard_account_deletion_irreversible_state")
        .contains("old.cancellation_requested and not new.cancellation_requested");
  }
}
