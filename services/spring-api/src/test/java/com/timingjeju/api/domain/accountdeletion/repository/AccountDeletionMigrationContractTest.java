package com.timingjeju.api.domain.accountdeletion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccountDeletionMigrationContractTest {
  @Test
  void migration은_profile_SET_NULL과_비밀필드_다섯상태_RLS_서버전용경계를_고정한다() throws Exception {
    String sql =
        Files.readString(
                Path.of("../../supabase/migrations/20260919000000_account_deletion_requests.sql"))
            .toLowerCase();

    assertThat(sql)
        .contains("account_deletion_requests")
        .contains("on delete set null")
        .contains("status_token_hash")
        .contains("status_token_ciphertext")
        .contains("status_token_key_version")
        .contains("auth_subject_ciphertext")
        .contains("auth_subject_key_version")
        .contains("'queued', 'running', 'succeeded', 'failed', 'cancelled'")
        .contains("enable row level security")
        .contains("revoke all on table public.account_deletion_requests from anon")
        .contains("revoke all on table public.account_deletion_requests from authenticated")
        .doesNotContain("grant insert on table public.account_deletion_requests to authenticated")
        .contains("security definer")
        .contains("set search_path = pg_catalog, auth")
        .contains("revoke all on function public.account_deletion_session_is_recent")
        .contains("grant execute on function public.account_deletion_session_is_recent");
    assertThat(Files.readString(Path.of("../../db/local-postgres/auth_compat.sql")).toLowerCase())
        .contains("create table if not exists auth.sessions")
        .doesNotContain("grant insert on auth.sessions");
  }

  @Test
  void security_correction은_subject_fingerprint와_cleanup_index를_additive하게_고정한다() throws Exception {
    String sql =
        Files.readString(
                Path.of(
                    "../../supabase/migrations/20260919030000_account_deletion_security_correction.sql"))
            .toLowerCase();

    assertThat(sql)
        .contains("auth_subject_fingerprint bytea")
        .contains("octet_length(auth_subject_fingerprint) = 32")
        .contains("status <> 'cancelled'")
        .contains("status_token_expires_at")
        .doesNotContain("grant insert")
        .doesNotContain("grant delete")
        .doesNotContain("auth.users");
  }
}
