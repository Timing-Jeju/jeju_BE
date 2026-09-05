package com.timingjeju.api.domain.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TimetableMigrationContractTest {
  private static final String MIGRATION = "20260915000000_jeju_timetable_route_scope.sql";
  private static final String TARGET =
      "/docker-entrypoint-initdb.d/046_jeju_timetable_route_scope.sql";

  @Test
  void migration은_시간표_source와_TAGO_route_reference_scope를_분리하고_legacy를_보존한다() throws Exception {
    String sql = Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));
    assertThat(sql)
        .contains("add column route_source_provider text")
        .contains("add column route_city_code text")
        .contains("set route_source_provider = source_provider")
        .contains("set route_city_code = city_code")
        .contains("not valid")
        .contains("new timetable row requires route reference scope")
        .contains("route_stop.source_provider = new.route_source_provider")
        .contains("route_stop.city_code = new.route_city_code")
        .doesNotContain("delete from public.timetable_entries")
        .doesNotContain("update public.timetable_entries\nset source_provider = 'JEJU_PROVINCE'");
  }

  @Test
  void migration은_정규화_manifest만_snapshot으로_허용하고_원본_binary를_금지한다() throws Exception {
    String sql = Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));
    assertThat(sql)
        .contains("JEJU_PROVINCE")
        .contains("3043887")
        .contains("external_api_snapshots")
        .contains("payload_format = 'JSON'")
        .contains("raw XLSX bytes must not be persisted");
  }

  @Test
  void manifest_constraint는_시간표_service만_닫고_다른_제주도_service는_막지_않는다() throws Exception {
    String sql = Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));
    assertThat(sql)
        .contains("or source_service <> 'jeju-bus-schedule-xlsx'")
        .contains("or source_operation <> 'timetable-import'")
        .contains("payload_format = 'JSON'")
        .contains("or source_operation <> 'timetable-import'")
        .contains("jeju_timetable_manifest_is_safe(raw_payload)")
        .contains("payload - array[")
        .contains("payload->>'datasetId' <> '3043887'")
        .contains("jsonb_typeof(record_key) <> 'string'")
        .contains("record_key #>> '{}') !~ '^3043887/40500(1|9)/")
        .contains("octet_length(payload::text) > 4194304")
        .doesNotContain("'raw'", "'body'", "'content'");
  }

  @Test
  void compose와_legacy_upgrade는_새_migration을_seed보다_먼저_적용한다() throws Exception {
    Path root = root();
    String source = "./supabase/migrations/" + MIGRATION;
    for (String compose : List.of("docker-compose.yml", "compose.yml", "compose.test.yml")) {
      String contents = Files.readString(root.resolve(compose));
      assertThat(contents).contains(source).contains(TARGET);
      assertThat(contents.indexOf(TARGET))
          .isLessThan(contents.indexOf("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"));
    }
    assertThat(
            Files.readString(root.resolve("scripts/docker-smoke-test.sh"))
                .split(java.util.regex.Pattern.quote(TARGET), -1))
        .hasSize(3);
  }

  private static Path root() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null && !Files.isDirectory(current.resolve("supabase/migrations")))
      current = current.getParent();
    if (current == null) throw new AssertionError("repository root");
    return current;
  }
}
