package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ScheduleItemCreateMigrationContractTest {
  private static final String MIGRATION = "20260907000000_schedule_item_create_contract.sql";
  private static final String REQUIRED_REFERENCE_MIGRATION =
      "20260907000001_schedule_item_required_references.sql";
  private static final String TARGET =
      "/docker-entrypoint-initdb.d/037_schedule_item_create_contract.sql";

  @Test
  void migration은_숙소와_교통이벤트를_동일여행_복합FK로_제한한다() throws Exception {
    String sql =
        Files.readString(repositoryRoot().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("add column accommodation_id uuid")
        .contains("add column transport_event_id uuid")
        .contains("foreign key (accommodation_id, trip_plan_id)")
        .contains("references public.trip_accommodations (id, trip_plan_id)")
        .contains("foreign key (transport_event_id, trip_plan_id)")
        .contains("references public.trip_transport_events (id, trip_plan_id)")
        .doesNotContain("raw_payload")
        .doesNotContain("geometry");
  }

  @Test
  void compose와_legacy_upgrade는_새_migration을_seed보다_먼저_적용한다() throws Exception {
    Path root = repositoryRoot();
    String source = "./supabase/migrations/" + MIGRATION;
    for (String compose : List.of("docker-compose.yml", "compose.yml", "compose.test.yml")) {
      String contents = Files.readString(root.resolve(compose));
      assertThat(contents).as(compose).contains(source).contains(TARGET);
      assertThat(contents.indexOf(TARGET))
          .as(compose)
          .isLessThan(contents.indexOf("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"));
    }

    String smoke = Files.readString(root.resolve("scripts/docker-smoke-test.sh"));
    assertThat(smoke.split(java.util.regex.Pattern.quote(TARGET), -1)).hasSize(3);
  }

  @Test
  void 후속_migration은_item_type별_필수_참조를_감사하고_모든_쓰기_경계에서_강제한다() throws Exception {
    Path migration =
        repositoryRoot().resolve("supabase/migrations").resolve(REQUIRED_REFERENCE_MIGRATION);

    assertThat(migration).isRegularFile();
    String sql = Files.readString(migration).toLowerCase().replaceAll("\\s+", " ").trim();
    String predicates = sql.replace("new.", "").replace("item.", "");

    int audit = sql.indexOf("legacy schedule item required reference audit failed");
    int constraint = sql.indexOf("add constraint trip_items_required_references_by_type");
    assertThat(audit).isGreaterThanOrEqualTo(0).isLessThan(constraint);
    assertThat(sql)
        .contains(
            "(item_type = 'accommodation' and accommodation_id is not null and transport_event_id is null)")
        .contains(
            "(item_type in ('arrival', 'departure') and transport_event_id is not null and accommodation_id is null)")
        .contains(
            "(item_type not in ('accommodation', 'arrival', 'departure') and accommodation_id is null and transport_event_id is null)")
        .contains("create trigger trg_validate_trip_item_required_references")
        .contains("create or replace function public.assert_schedule_item_required_references")
        .contains(
            "perform public.assert_schedule_item_required_references(new.id, new.trip_plan_id)")
        .contains(
            "revoke execute on function public.assert_schedule_item_required_references(uuid, uuid) from public, anon, authenticated")
        .contains(
            "grant execute on function public.assert_schedule_item_required_references(uuid, uuid) to service_role")
        .doesNotContain("delete from public.trip_items")
        .doesNotContain("update public.trip_items");
    for (String branch :
        List.of(
            "(item_type = 'accommodation' and accommodation_id is not null and transport_event_id is null)",
            "(item_type in ('arrival', 'departure') and transport_event_id is not null and accommodation_id is null)",
            "(item_type not in ('accommodation', 'arrival', 'departure') and accommodation_id is null and transport_event_id is null)")) {
      assertThat(predicates.split(java.util.regex.Pattern.quote(branch), -1)).as(branch).hasSize(5);
    }
  }

  @Test
  void canonical_DB_contract는_schema_negative_legacy_upgrade의_각_경계를_검증한다() throws Exception {
    Path root = repositoryRoot();
    String schema = Files.readString(root.resolve("db/queries/schema_contract.sql"));
    String negative =
        Files.readString(root.resolve("db/queries/database_negative_constraints.sql"));
    String legacyFixture =
        Files.readString(
            root.resolve("db/queries/legacy_schedule_item_reference_conflict_fixture.sql"));
    String legacyContract =
        Files.readString(root.resolve("db/queries/legacy_v1_upgrade_contract.sql"));
    String smoke = Files.readString(root.resolve("scripts/docker-smoke-test.sh"));

    assertThat(schema)
        .contains("trip_items_required_references_by_type")
        .contains("trg_validate_trip_item_required_references")
        .contains("assert_schedule_item_required_references(uuid,uuid)")
        .contains("schedule item required references function privilege boundary is invalid");
    assertThat(negative)
        .contains("accommodation item requires accommodation reference")
        .contains("arrival item requires transport event reference")
        .contains("place item forbids accommodation reference")
        .contains("accommodation item forbids transport event reference")
        .contains("sealed schedule rejects invalid required reference");
    assertThat(legacyFixture)
        .contains("e4300000-0000-0000-0000-000000000001")
        .contains("drop constraint trip_items_required_references_by_type");
    assertThat(legacyContract)
        .contains("valid legacy schedule item required references were not preserved");
    assertThat(smoke)
        .contains("legacy_schedule_item_reference_conflict_fixture.sql")
        .contains("legacy schedule item required reference audit failed");
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("supabase/migrations"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
