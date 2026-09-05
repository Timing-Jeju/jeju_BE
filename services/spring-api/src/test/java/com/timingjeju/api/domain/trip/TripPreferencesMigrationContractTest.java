package com.timingjeju.api.domain.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripPreferencesMigrationContractTest {
  private static final String MIGRATION = "20260907000003_trip_preferences_replace_contract.sql";
  private static final String OWNER_READ_MIGRATION =
      "20260907000004_trip_preferences_owner_read_helper.sql";

  @Test
  void migration은_legacy를_fail_closed감사하고_row및_deferred_aggregate제약을_고정한다() throws Exception {
    String sql = source().toLowerCase();

    assertThat(sql)
        .startsWith("begin;")
        .endsWith("commit;\n")
        .contains("trip_preferences")
        .contains("trip_transport_modes")
        .contains("raise exception")
        .contains("validate_trip_transport_mode_set")
        .contains("deferrable initially deferred")
        .contains("trip_transport_modes_aggregate_check")
        .contains("primary_count")
        .contains("primary_priority")
        .contains("priority")
        .contains("public_transit")
        .contains("rental_car")
        .contains("taxi")
        .doesNotContain("delete from public.trip_preferences")
        .doesNotContain("delete from public.trip_transport_modes");
    assertThat(count(sql, "create constraint trigger")).isEqualTo(2);
    assertThat(sql.indexOf("raise exception"))
        .isLessThan(sql.indexOf("create or replace function"));
  }

  @Test
  void migration은_RLS와_service_role최소권한을_replay_safe하게고정하고_policy를_추가하지않는다() throws Exception {
    String sql = compact(source());

    assertThat(sql)
        .contains("enable row level security")
        .contains("revoke all")
        .contains("from anon, authenticated")
        .contains("security definer")
        .contains("set search_path = pg_catalog, public")
        .contains(
            "revoke execute on function public.validate_trip_transport_mode_set() from public,"
                + " anon, authenticated")
        .contains("grant select on public.trip_preferences to authenticated")
        .contains("grant select on public.trip_transport_modes to authenticated")
        .contains("grant select, insert, update, delete")
        .contains("to service_role")
        .doesNotContain("grant truncate")
        .doesNotContain("create policy")
        .doesNotContain("drop policy")
        .doesNotContain("alter policy")
        .doesNotContain("flyway");
    for (String signature :
        java.util.List.of(
            "public.trip_preference_categories_valid(text[])",
            "public.trip_preference_regions_valid(text[])",
            "public.trip_preference_ascii_trim(text)",
            "public.validate_trip_transport_mode_set()")) {
      assertThat(sql)
          .contains(
              "revoke execute on function " + signature + " from public, anon, authenticated");
    }
    assertThat(sql)
        .contains(
            "grant execute on function public.trip_preference_categories_valid(text[]) to"
                + " service_role")
        .contains(
            "grant execute on function public.trip_preference_regions_valid(text[]) to service_role")
        .contains(
            "grant execute on function public.trip_preference_ascii_trim(text) to service_role")
        .doesNotContain(
            "grant execute on function public.validate_trip_transport_mode_set() to service_role");
  }

  @Test
  void aggregate관계는_mode_only를허용하고_preference_only만거부한다() throws Exception {
    String sql = compact(source());

    assertThat(sql)
        .contains("(aggregate_state.mode_count = 0 and aggregate_state.preference_count <> 0)")
        .contains("preference_count = 0 and mode_count = 0")
        .contains("mode_count between 1 and 3")
        .doesNotContain("preference_count = 1 and mode_count between 1 and 3");
    assertThat(count(sql, "preference_count = 0 and mode_count = 0")).isEqualTo(2);
    assertThat(count(sql, "mode_count between 1 and 3")).isEqualTo(2);
  }

  @Test
  void region_trim은_여섯_ASCII공백만사용하고_다른_C0문자를금지하지않는다() throws Exception {
    String sql = source().toLowerCase();
    String compact = compact(sql);
    String audit = sql.substring(0, sql.indexOf("create or replace function"));
    String trimCharacters = "e' \\t\\n\\r\\f\\013'";

    assertThat(audit).contains("btrim(").contains(trimCharacters);
    assertThat(compact)
        .contains("create or replace function public.trip_preference_ascii_trim")
        .contains("returns text")
        .contains("immutable")
        .contains("strict")
        .contains("parallel safe")
        .contains("btrim(value, " + trimCharacters + ")")
        .contains(
            "revoke execute on function public.trip_preference_ascii_trim(text) from public, anon,"
                + " authenticated")
        .contains(
            "revoke execute on function public.trip_preference_ascii_trim(text) from service_role")
        .contains(
            "grant execute on function public.trip_preference_ascii_trim(text) to service_role")
        .contains(
            "arrival_region_code =" + " public.trip_preference_ascii_trim(arrival_region_code)")
        .contains(
            "departure_region_code =" + " public.trip_preference_ascii_trim(departure_region_code)")
        .contains("public.trip_preference_ascii_trim(region_code)");
    assertThat(sql).doesNotContain("[:cntrl:]").doesNotContain("[\\x00-\\x1f]");
  }

  @Test
  void compose세개는_040과_041을_099_seed보다먼저_mount하고_smoke가_residue0을검사한다() throws Exception {
    Path root = root();
    String replaceContractMount =
        "./supabase/migrations/"
            + MIGRATION
            + ":/docker-entrypoint-initdb.d/040_trip_preferences_replace_contract.sql:ro";
    String ownerReadMount =
        "./supabase/migrations/"
            + OWNER_READ_MIGRATION
            + ":/docker-entrypoint-initdb.d/041_trip_preferences_owner_read_helper.sql:ro";
    String seed =
        "./db/local-postgres/seed_fixtures.sql:/docker-entrypoint-initdb.d/099_seed_fixtures.sql:ro";
    for (String compose :
        java.util.List.of("compose.yml", "compose.test.yml", "docker-compose.yml")) {
      String text = Files.readString(root.resolve(compose));
      assertThat(text).contains(replaceContractMount).contains(ownerReadMount).contains(seed);
      assertThat(text.indexOf(replaceContractMount)).isLessThan(text.indexOf(ownerReadMount));
      assertThat(text.indexOf(ownerReadMount)).isLessThan(text.indexOf(seed));
    }
    assertThat(Files.readString(root.resolve("scripts/docker-smoke-test.sh")))
        .contains("/docker-entrypoint-initdb.d/040_trip_preferences_replace_contract.sql")
        .contains("/docker-entrypoint-initdb.d/041_trip_preferences_owner_read_helper.sql")
        .contains("residue");
  }

  @Test
  void smoke_ACL은_authenticated_owner조회두테이블의_SELECT만_exact예외로허용한다() throws Exception {
    String smoke = compact(Files.readString(root().resolve("db/queries/smoke_check.sql")));

    assertThat(smoke)
        .contains(
            "grantee = 'authenticated' and table_name in ('trip_preferences',"
                + " 'trip_transport_modes') and privilege_type = 'select'")
        .doesNotContain(
            "grantee = 'anon' and table_name in ('trip_preferences', 'trip_transport_modes')")
        .doesNotContain(
            "table_name in ('trip_preferences', 'trip_transport_modes') and privilege_type <>"
                + " 'select'");
    assertThat(count(smoke, "grantee = 'authenticated'")).isEqualTo(2);
  }

  private static String source() throws Exception {
    return Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));
  }

  private static int count(String source, String token) {
    return (source.length() - source.replace(token, "").length()) / token.length();
  }

  private static String compact(String source) {
    return source.toLowerCase().replaceAll("\\s+", " ").trim();
  }

  private static Path root() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isDirectory(path.resolve("supabase/migrations"))) {
      path = path.getParent();
    }
    if (path == null) throw new AssertionError("repository root not found");
    return path;
  }
}
