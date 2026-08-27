package com.timingjeju.api.domain.savedplaces.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SavedPlacesMigrationContractTest {
  private static final Path MIGRATION =
      Path.of("../../supabase/migrations/20260903000000_saved_places_api.sql");

  @Test
  void migration은_후속_timestamp와_032_mount로_모든_compose와_smoke순서에_포함된다() throws Exception {
    assertThat(MIGRATION).exists();
    String source = "./supabase/migrations/20260903000000_saved_places_api.sql";
    String target = "/docker-entrypoint-initdb.d/032_saved_places_api.sql";
    for (String compose :
        List.of("../../compose.yml", "../../compose.test.yml", "../../docker-compose.yml")) {
      String contents = Files.readString(Path.of(compose));
      assertThat(contents).contains(source).contains(target);
      assertThat(contents.indexOf(target))
          .isLessThan(contents.indexOf("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"));
    }
    String smoke = Files.readString(Path.of("../../scripts/docker-smoke-test.sh"));
    assertThat(smoke).contains(target);
    assertThat(smoke.split(java.util.regex.Pattern.quote(target), -1)).hasSize(3);
  }

  @Test
  void actualPG_harness는_supported_local_security와_psql_runner를_사용한다() throws Exception {
    String http =
        Files.readString(
            Path.of(
                "src/test/java/com/timingjeju/api/domain/savedplaces/controller/SavedPlacesHttpPostgreSqlIntegrationTest.java"));
    assertThat(http)
        .contains("spring.profiles.active=local-hs256")
        .contains("app.security.jwt.secret=test-")
        .contains("only-hs256-secret-with-at-least-32-bytes")
        .doesNotContain("@ActiveProfiles(\"postgresql-integration\")")
        .doesNotContain("SecureRandom");

    String migration =
        Files.readString(
            Path.of(
                "src/test/java/com/timingjeju/api/support/postgresql/SavedPlacesMigrationIntegrationTest.java"));
    assertThat(migration)
        .contains("PostgreSqlTestContainerFactory.createBefore(TARGET)")
        .contains("PostgreSqlTestContainerFactory.executeScript(container, target)")
        .doesNotContain("ScriptUtils")
        .doesNotContain("FileSystemResource");
  }

  @Test
  void migration은_loss_aware_backfill과_trim_NFC_order_direct_DML제약을_명시한다() throws Exception {
    String sql = Files.readString(MIGRATION);
    assertThat(sql)
        .contains("saved_places_backfill_audit")
        .contains("original_memo")
        .contains("original_tags")
        .contains("normalize(value, NFC)")
        .contains("value <> btrim(value)")
        .contains("order by canonical_value collate \"C\"")
        .contains("memo = btrim(memo)")
        .contains("memo = normalize(memo, NFC)")
        .contains("ck_saved_places_priority_range")
        .contains("ck_saved_places_target_day_range");
  }

  @Test
  void idempotency_marker는_place_hard_delete를_막지_않고_expiry_index_cleanup을_제공한다() throws Exception {
    String sql = Files.readString(MIGRATION);
    assertThat(sql)
        .doesNotContain("place_id uuid not null references public.tour_places")
        .contains("response_status smallint")
        .contains("response_content_type text")
        .contains("response_location text")
        .contains("response_body bytea")
        .contains("owner_sub uuid not null references auth.users(id) on delete cascade")
        .contains(
            "num_nonnulls(response_status,response_content_type,response_location,response_body) = 0")
        .contains(
            "num_nonnulls(response_status,response_content_type,response_location,response_body) = 4")
        .contains("(response_status in (200,201)) is true")
        .contains("(response_content_type = 'application/json') is true")
        .contains("ix_saved_place_idempotency_expiry");
    String retention =
        Files.readString(
            Path.of(
                "src/main/java/com/timingjeju/api/domain/savedplaces/repository/SavedPlaceIdempotencyRetentionRepository.java"));
    assertThat(retention)
        .contains("implements SavedPlaceRetentionTask")
        .contains("PROPAGATION_REQUIRES_NEW")
        .contains("for update skip locked")
        .contains("limit 100")
        .contains("public int drain(int maxBatches)");
  }

  @Test
  void ACL은_authenticated를_차단하고_service_role에_최소_DML만_허용한다() throws Exception {
    String sql = Files.readString(MIGRATION);
    String initial =
        Files.readString(
            Path.of("../../supabase/migrations/20260728000000_initial_public_schema.sql"));
    String smoke = Files.readString(Path.of("../../db/queries/smoke_check.sql"));
    assertThat(sql)
        .contains("revoke all privileges on table public.saved_places from authenticated")
        .contains("revoke all privileges on table public.saved_places from service_role")
        .contains("grant select,insert,update,delete on public.saved_places to service_role")
        .contains("revoke all privileges on table public.saved_place_idempotency from service_role")
        .contains(
            "grant select,insert,update,delete on public.saved_place_idempotency to service_role")
        .doesNotContain("create policy saved_places_owner_insert")
        .doesNotContain("create policy saved_places_owner_update")
        .doesNotContain("create policy saved_places_owner_delete")
        .doesNotContain(
            "grant select,insert,update,delete on public.saved_places to authenticated");
    assertThat(savedPlacesWritePolicies(sql)).isEmpty();
    assertThat(
            savedPlacesWritePolicies(
                sql
                    + "\ncreate policy mutation_probe on public.saved_places for update to authenticated using (true);"))
        .containsExactly("update");
    assertThat(
            savedPlacesWritePolicies(
                sql
                    + "\ncreate policy omitted_for_probe on public.saved_places to authenticated using (true);"))
        .containsExactly("all");
    assertThat(initial)
        .contains("create policy saved_places_owner_select")
        .contains("on saved_places for select to authenticated");
    assertThat(smoke)
        .contains("from pg_policies")
        .contains("and cmd <> 'SELECT'")
        .contains("client-write RLS policies must not exist");
  }

  private static List<String> savedPlacesWritePolicies(String sql) {
    var matcher =
        Pattern.compile(
                "create\\s+policy\\s+\\S+\\s+on\\s+(?:public\\.)?saved_places\\b(?:(?!;).)*?(?:\\bfor\\s+(select|insert|update|delete|all)\\b|;)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(sql);
    var commands = new java.util.ArrayList<String>();
    while (matcher.find()) {
      String command = matcher.group(1);
      if (command == null) {
        commands.add("all");
      } else if (!command.equalsIgnoreCase("select")) {
        commands.add(command.toLowerCase(java.util.Locale.ROOT));
      }
    }
    return commands;
  }

  @Test
  void raw_backfill_audit은_owner_delete와_30일_retention에_묶인다() throws Exception {
    String sql = Files.readString(MIGRATION);
    assertThat(sql)
        .contains("user_id uuid references public.user_profiles(id) on delete cascade")
        .contains("session_id uuid references public.app_sessions(id) on delete cascade")
        .contains(
            "create index ix_saved_places_backfill_user_fk\n  on public.saved_places_backfill_audit(user_id)")
        .contains(
            "create index ix_saved_places_backfill_session_fk\n  on public.saved_places_backfill_audit(session_id)")
        .contains("purge_after timestamptz not null")
        .contains("ix_saved_places_backfill_purge")
        .contains("purge_after <= captured_at + interval '30 days'");
  }

  @Test
  void legacy_dual_owner는_audit에_양쪽_provenance를_보존한뒤_live를_user_owner로_canonicalize한다()
      throws Exception {
    String sql = Files.readString(MIGRATION);
    assertThat(sql)
        .contains("'legacy_dual_owner'")
        .contains("check (num_nonnulls(user_id,session_id) between 1 and 2)")
        .contains("session_id = case when user_id is not null then null else session_id end")
        .contains("drop constraint saved_places_check")
        .contains("check (num_nonnulls(user_id,session_id) = 1)");
  }
}
