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
        .startsWith("-- Issue #38")
        .contains("begin;")
        .contains("lock table public.timetable_entries in access exclusive mode")
        .contains("add column route_source_provider text")
        .contains("add column route_city_code text")
        .contains("set route_source_provider = source_provider")
        .contains("route_city_code = city_code")
        .contains("timetable route scope backfill violated audited old/new scope")
        .contains("old.route_source_provider is null")
        .contains("new.route_source_provider is not distinct from old.source_provider")
        .contains("new.route_city_code is not distinct from old.city_code")
        .contains(
            "pg_catalog.to_jsonb(new) - array['route_source_provider', 'route_city_code']::text[]")
        .contains(
            "revoke execute on function public.validate_timetable_source_scope() from public, anon, authenticated")
        .contains("not valid")
        .contains("new timetable row requires route reference scope")
        .contains("route_stop.source_provider = new.route_source_provider")
        .contains("route_stop.city_code = new.route_city_code")
        .doesNotContain("delete from public.timetable_entries")
        .doesNotContain("update public.timetable_entries\nset source_provider = 'JEJU_PROVINCE'")
        .endsWith("commit;\n");
    assertThat(
            sql.split(
                "create or replace function public.validate_timetable_source_scope\\(\\)", -1))
        .hasSize(3);
    assertThat(
            sql.split(
                "revoke execute on function public.validate_timetable_source_scope\\(\\) from public, anon, authenticated",
                -1))
        .hasSize(3);
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
        .contains("jeju_timetable_manifest_is_safe(raw_payload, request_metadata_redacted)")
        .contains("payload - array[")
        .contains("payload->>'datasetId' <> '3043887'")
        .contains("jsonb_typeof(payload->'effectiveDate') <> 'string'")
        .contains("payload->>'effectiveDate' <> request_metadata->>'effectiveDate'")
        .contains("request_metadata - array['effectiveDate', 'omissionCount']::text[]")
        .contains("jsonb_typeof(request_metadata->'effectiveDate') <> 'string'")
        .contains("jsonb_typeof(request_metadata->'omissionCount') <> 'number'")
        .contains("payload->'omissionCount' is distinct from request_metadata->'omissionCount'")
        .contains("jsonb_typeof(record_key) <> 'string'")
        .contains("record_key #>> '{}') !~ '^3043887/40500(1|9)/")
        .contains("(payload->>'omissionCount') !~ '^(0|[1-9][0-9]{0,5})$'")
        .contains("octet_length(payload::text) > 2097152")
        .doesNotContain("'raw'", "'body'", "'content'");
  }

  @Test
  void parser의_상세_lineage_manifest_key와_omission_schema를_DB에서도_exact하게_허용한다() throws Exception {
    String sql = Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));
    assertThat(sql)
        .contains("'datasetId', 'scheduleId', 'effectiveDate', 'mappingVersion', 'omissionCount',")
        .contains("'parserVersion', 'recordKeys', 'omissions'")
        .contains("jsonb_typeof(payload->'scheduleId') <> 'string'")
        .contains("payload->>'scheduleId' !~ '^40500(1|9)$'")
        .contains("jsonb_typeof(payload->'omissions') <> 'array'")
        .contains(
            "(payload->>'omissionCount')::integer <> jsonb_array_length(payload->'omissions')")
        .contains("jsonb_array_length(payload->'omissions') > 126")
        .contains(
            "for omission_detail in select value from jsonb_array_elements(payload->'omissions')")
        .contains("jsonb_typeof(omission_detail) <> 'string'")
        .contains("101 남원-성산-김녕-조천-공항")
        .contains("101 공항-조천-김녕-성산-남원")
        .contains("201 서귀포터미널-남원-성산-세화-조천-제주터미널")
        .contains("201 제주터미널-조천-세화-성산-남원-서귀포터미널")
        .contains("/UNRESOLVED_OFFICIAL_COLUMN_OMITTED$")
        .contains("split_part(omission_detail #>> '{}', '/row=', 2)")
        .contains("split_part(omission_detail #>> '{}', '/column=', 2)")
        .contains("omission_row > 10000")
        .contains("omission_column > 256")
        .contains("payload->>'scheduleId' = '405001' and omission_text !~ '^101 '")
        .contains("payload->>'scheduleId' = '405009' and omission_text !~ '^201 '")
        .contains("'3043887/' || (payload->>'scheduleId') || '/'")
        .doesNotContain("'3043887/' || payload->>'scheduleId' || '/'");
  }

  @Test
  void 교체된_trigger_function도_source_city_scope의_신규행_non_null을_보존한다() throws Exception {
    String sql = Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));
    assertThat(sql)
        .contains("if new.city_code is null or btrim(new.city_code) = '' then")
        .contains("new timetable row requires provider city scope")
        .contains("create or replace function public.validate_timetable_source_scope()")
        .doesNotContain("drop trigger trg_timetable_entries_validate_source_scope");
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
