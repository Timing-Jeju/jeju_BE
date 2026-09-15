package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class PlannedRouteMigrationIntegrationTest {
  private static final String TARGET = "20260918000015_planned_route_snapshot_provenance.sql";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 명시적_공개_계획_계보가_증명된_합성_legacy_route는_새_hash로_보존한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("db/local-postgres/legacy_planned_route_provenance_fixture.sql"));
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("supabase/migrations").resolve(TARGET));
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      var preserved =
          jdbc.queryForMap(
              """
          select snapshot.anchor_contract_version,snapshot.request_hash,
                 snapshot.schedule_version_id=leg.schedule_version_id as version_matches,
                 snapshot.origin_item_id=leg.from_item_id as origin_matches,
                 snapshot.destination_item_id=leg.to_item_id as destination_matches,
                 snapshot.owner_user_id=trip.user_id as owner_matches
          from public.mobility_route_snapshots snapshot join public.trip_legs leg
            on leg.mobility_route_snapshot_id=snapshot.id
          join public.trip_plans trip on trip.id=leg.trip_plan_id
          """);
      assertThat(preserved)
          .containsEntry("anchor_contract_version", "planned-anchor.v1")
          .containsEntry("version_matches", true)
          .containsEntry("origin_matches", true)
          .containsEntry("destination_matches", true)
          .containsEntry("owner_matches", true);
      assertThat((String) preserved.get("request_hash")).matches("^[0-9a-f]{64}$");
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "postgis/postgis:16-3.4,unreferenced", "postgis/postgis:17-3.5,unreferenced",
    "postgis/postgis:16-3.4,coordinate_mismatch", "postgis/postgis:17-3.5,coordinate_mismatch",
    "postgis/postgis:16-3.4,active_ambiguous", "postgis/postgis:17-3.5,active_ambiguous"
  })
  void 출처_불명이나_활성_모호한_legacy_route는_데이터와_스키마_권한을_보존하고_중단한다(String image, String kind)
      throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("db/local-postgres/legacy_planned_route_provenance_fixture.sql"));
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      switch (kind) {
        case "unreferenced" ->
            jdbc.update(
                """
            insert into public.mobility_route_snapshots
              (request_hash,origin_location,destination_location,transport_mode,departure_at,
               distance_meters,duration_minutes,estimated_fare,source_provider,source_operation,route_summary,expires_at)
            select 'unreferenced-private-marker',origin_location,destination_location,transport_mode,departure_at,
                   distance_meters,duration_minutes,estimated_fare,source_provider,source_operation,route_summary,expires_at
            from public.mobility_route_snapshots
            """);
        case "coordinate_mismatch" ->
            jdbc.update(
                "update public.mobility_route_snapshots set origin_location=ST_SetSRID(ST_MakePoint(127.7,34.7),4326)::geography");
        case "active_ambiguous" ->
            jdbc.execute(
                """
            do $$
            declare
              original public.trip_legs%rowtype;
              new_version uuid := gen_random_uuid();
              new_origin uuid := gen_random_uuid();
              new_destination uuid := gen_random_uuid();
            begin
              select * into original from public.trip_legs;
              insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type)
              values (new_version,original.trip_plan_id,2,'draft','user_edit');
              insert into public.trip_items
                (id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,
                 planned_start_at,planned_end_at,stay_minutes,source)
              select case when id=original.from_item_id then new_origin else new_destination end,
                     trip_plan_id,trip_day_id,new_version,sequence_no,item_type,place_id,
                     planned_start_at,planned_end_at,stay_minutes,source
              from public.trip_items where schedule_version_id=original.schedule_version_id;
              insert into public.trip_legs
                (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,
                 transport_mode,mobility_route_snapshot_id,planned_departure_at,planned_arrival_at,
                 walk_minutes,wait_minutes,ride_minutes,transfer_minutes,duration_minutes,buffer_minutes,
                 distance_meters,estimated_fare)
              values (original.trip_plan_id,original.trip_day_id,new_version,1,new_origin,new_destination,
                      original.transport_mode,original.mobility_route_snapshot_id,original.planned_departure_at,
                      original.planned_arrival_at,original.walk_minutes,original.wait_minutes,original.ride_minutes,
                      original.transfer_minutes,original.duration_minutes,original.buffer_minutes,
                      original.distance_meters,original.estimated_fare);
            end;
            $$;
            """);
        default -> throw new IllegalArgumentException(kind);
      }
      String schemaSql =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String dataSql =
          """
          select md5(jsonb_build_object(
            'routes',(select jsonb_agg(row_data order by id) from public.mobility_route_snapshots row_data),
            'legs',(select jsonb_agg(row_data order by id) from public.trip_legs row_data),
            'items',(select jsonb_agg(row_data order by id) from public.trip_items row_data),
            'versions',(select jsonb_agg(row_data order by id) from public.trip_schedule_versions row_data),
            'trips',(select jsonb_agg(row_data order by id) from public.trip_plans row_data))::text)
          """;
      String beforeSchema = jdbc.queryForObject(schemaSql, String.class);
      String beforeData = jdbc.queryForObject(dataSql, String.class);
      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container, root.resolve("supabase/migrations").resolve(TARGET)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ERROR: 23514")
          .hasMessageContaining("cause=integrity-constraint")
          .hasMessageNotContaining("legacy route snapshots require provenance audit")
          .hasMessageNotContaining("unreferenced-private-marker")
          .hasMessageNotContaining("127.7")
          .hasMessageNotContaining("34.7")
          .hasMessageNotContaining("Failing row");
      assertThat(jdbc.queryForObject(schemaSql, String.class)).isEqualTo(beforeSchema);
      assertThat(jdbc.queryForObject(dataSql, String.class)).isEqualTo(beforeData);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 현재_seed는_빈_item_facts와_버전별_공개_route_계보로_적재된다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(
          container,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("supabase/migrations")
              .resolve(TARGET));
      PostgreSqlTestContainerFactory.executeScript(
          container,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("db/local-postgres/seed_fixtures.sql"));
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.mobility_route_snapshots "
                      + "where anchor_contract_version='planned-anchor.v1'",
                  Integer.class))
          .isEqualTo(4);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_items where facts<>'{}'::jsonb", Integer.class))
          .isZero();
    } finally {
      container.stop();
    }
  }
}
