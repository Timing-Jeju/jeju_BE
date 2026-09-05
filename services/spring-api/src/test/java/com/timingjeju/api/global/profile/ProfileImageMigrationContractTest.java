package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageMigrationContractTest {

  private static final Path MIGRATION =
      Path.of("../../supabase/migrations/20260913000000_profile_image_storage.sql");

  @Test
  void user_profiles는_URL대신_generation_source_strong_ETag_version을_저장한다() throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    assertThat(sql)
        .contains(
            "profile_image_object_key text",
            "profile_image_source text",
            "profile_image_storage_etag text",
            "profile_image_version bigint")
        .contains("profile_image_version >= 0")
        .contains("profile_image_source in ('provider', 'storage', 'none')")
        .contains("profile_image_object_key is not null")
        .contains("profile_image_storage_etag is not null")
        .doesNotContain("signed_url", "service_role_key");
  }

  @Test
  void cleanup_outbox는_exact_generation_fence와_lease_retry_lifecycle을_갖는다() throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    assertThat(sql)
        .contains(
            "create table if not exists public.profile_image_cleanup_outbox",
            "owner_user_id uuid",
            "object_key text",
            "storage_etag text",
            "source_profile_version bigint",
            "reason text",
            "status text",
            "claim_token uuid",
            "claimed_at timestamptz",
            "attempt_count integer",
            "next_attempt_at timestamptz",
            "completed_at timestamptz")
        .contains("unique (object_key, storage_etag)")
        .contains(
            "constraint ck_profile_image_cleanup_object_key",
            "constraint ck_profile_image_cleanup_storage_etag")
        .contains("'replacement', 'clear', 'orphan', 'account_deletion'")
        .contains("'pending', 'claimed', 'succeeded', 'retry'")
        .doesNotContain("state text");
  }

  @Test
  void profile_images_bucket은_public_read와_owner_insert_only를_강제한다() throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    assertThat(sql)
        .contains("profile-images", "image/jpeg", "image/png", "image/webp", "5242880")
        .contains("create policy profile_images_owner_insert")
        .contains("create policy profile_images_owner_select")
        .contains("create policy profile_images_anon_insert_guard")
        .contains("create policy profile_images_anon_select_guard")
        .contains("create policy profile_images_bucket_insert_guard")
        .contains("create policy profile_images_bucket_select_guard")
        .contains("create policy profile_images_bucket_update_guard")
        .contains("create policy profile_images_bucket_delete_guard")
        .contains("as restrictive", "case when bucket_id <> ''profile-images'' then true", "else (")
        .contains("for insert", "auth.uid()")
        .doesNotContain(
            "create policy profile_images_owner_update",
            "create policy profile_images_owner_delete");
  }

  @Test
  void Storage_RLS는_role과_bucket경계를_exact하게_고정한다() throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    assertThat(sql)
        .contains(
            "'profile-images',\n        'profile-images',\n        true,",
            "as permissive for insert to authenticated",
            "owner_id = (select auth.uid()::text)",
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            "as restrictive for update to authenticated",
            "as restrictive for delete to authenticated",
            "as restrictive for insert to anon",
            "as restrictive for select to anon",
            "case when bucket_id <> ''profile-images'' then true")
        .doesNotContain(
            "for update to authenticated\n      using (bucket_id = ''profile-images''",
            "for delete to authenticated\n      using (bucket_id = ''profile-images''");
  }

  @Test
  void Storage_INSERT_RLS는_user_metadata_generation이나_preinsert_metadata를_인가에_사용하지_않는다()
      throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    // generation/MIME/실제 크기는 업로드 뒤 Storage info/HEAD로 검증한다. INSERT 인가는
    // canonical owner/key만 사용하며 사용자 제공 metadata를 권한 근거로 삼지 않는다.
    assertThat(sql)
        .contains("create policy profile_images_owner_insert")
        .doesNotContain(
            "user_metadata ->> ''generation''",
            "metadata ->> ''generation''",
            "metadata ->> ''mimetype''",
            "metadata ->> ''size''",
            "metadata ->> ''contentlength''");
  }

  @Test
  void storage_schema가_없는_plain_PostgreSQL은_DO_block을_skip하고_transition_test가_이를_실행한다()
      throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();
    String transition =
        Files.readString(
            Path.of(
                "src/test/java/com/timingjeju/api/support/postgresql/ProfileImageMigrationIntegrationTest.java"));

    assertThat(sql)
        .contains(
            "if to_regclass('storage.buckets') is not null",
            "and to_regclass('storage.objects') is not null then")
        .doesNotContain("create schema storage", "create table storage.objects");
    assertThat(transition)
        .contains("PostgreSqlTestContainerFactory.createBefore(TARGET)")
        .contains("PostgreSqlTestContainerFactory.executeScript(container, targetPath())");
  }

  @Test
  void cleanup_outbox는_anon_authenticated를_차단하고_service_role만_허용한다() throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    assertThat(sql)
        .contains(
            "revoke all on table public.profile_image_cleanup_outbox from public",
            "revoke all on table public.profile_image_cleanup_outbox from anon",
            "revoke all on table public.profile_image_cleanup_outbox from authenticated",
            "revoke all on table public.profile_image_cleanup_outbox from service_role",
            "grant select, insert, update on table public.profile_image_cleanup_outbox to service_role");
  }

  @Test
  void trigger_helper는_fixed_search_path와_least_privilege_EXECUTE를_갖는다() throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    assertThat(sql)
        .contains(
            "create or replace function public.sync_provider_profile_image_source()",
            "set search_path = ''",
            "revoke all on function public.sync_provider_profile_image_source() from public",
            "revoke all on function public.sync_provider_profile_image_source() from anon",
            "revoke all on function public.sync_provider_profile_image_source() from authenticated",
            "revoke all on function public.sync_provider_profile_image_source() from service_role")
        .doesNotContain("security definer");
  }

  @Test
  void 기존_api_idempotency_registry를_재사용하고_별도_table을_만들지_않는다() throws Exception {
    String sql = Files.readString(MIGRATION).toLowerCase();

    assertThat(sql)
        .doesNotContain("create table public.profile_image_idempotency")
        .doesNotContain("create table profile_image_idempotency");
  }

  @Test
  void 모든_PostgreSQL_compose가_profile_image_migration을_동일_순서로_mount한다() throws Exception {
    String mount =
        "./supabase/migrations/20260913000000_profile_image_storage.sql:"
            + "/docker-entrypoint-initdb.d/045_profile_image_storage.sql:ro";
    for (String compose :
        new String[] {"../../compose.yml", "../../compose.test.yml", "../../docker-compose.yml"}) {
      assertThat(Files.readString(Path.of(compose))).contains(mount);
    }
    String smoke = Files.readString(Path.of("../../scripts/docker-smoke-test.sh"));
    long executableMounts =
        smoke
            .lines()
            .map(String::strip)
            .filter(
                line -> line.equals("/docker-entrypoint-initdb.d/045_profile_image_storage.sql \\"))
            .count();
    assertThat(executableMounts).isEqualTo(2);
    assertThat(Files.readString(Path.of("../../docs/ARCHITECTURE.md")))
        .contains("20260913000000` `#78`");
  }
}
