package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class PlannedRouteReferenceIndexIntegrationTest {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 후속_index_migration은_해당_버전의_계획_route_FK와_index를_만족한다(String image) throws Exception {
    String migration = "20260918000016_planned_route_reference_integrity.sql";
    var container = PostgreSqlTestContainerFactory.createBefore(migration, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("supabase/migrations").resolve(migration));
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      var expected = new LinkedHashMap<String, String>();
      expected.put("owner", "trip_plan_id, owner_user_id");
      expected.put("version", "schedule_version_id, trip_plan_id");
      expected.put("origin_item", "origin_item_id, schedule_version_id, trip_plan_id");
      expected.put("destination_item", "destination_item_id, schedule_version_id, trip_plan_id");
      for (String endpoint : new String[] {"origin", "destination"}) {
        for (String reference :
            new String[] {
              "source_place_id",
              "source_stop_id",
              "place_ref_id",
              "stop_ref_id",
              "accommodation_ref_id",
              "transport_event_ref_id"
            }) {
          expected.put(endpoint + "_" + reference, endpoint + "_" + reference);
        }
      }
      expected.forEach(
          (suffix, columns) ->
              assertThat(
                      jdbc.queryForObject(
                          "select indexdef from pg_indexes where schemaname='public' "
                              + "and tablename='mobility_route_snapshots' and indexname=?",
                          String.class,
                          "idx_route_ref_" + suffix))
                  .isEqualTo(
                      "CREATE INDEX idx_route_ref_"
                          + suffix
                          + " ON public.mobility_route_snapshots USING btree ("
                          + columns
                          + ")"));
      assertThat(
              jdbc.queryForObject(
                  "select pg_get_constraintdef(oid) from pg_constraint "
                      + "where conrelid='public.mobility_route_snapshots'::regclass "
                      + "and conname='fk_route_planned_owner' and convalidated",
                  String.class))
          .isEqualTo(
              "FOREIGN KEY (trip_plan_id, owner_user_id) "
                  + "REFERENCES trip_plans(id, user_id) ON DELETE CASCADE");
    } finally {
      container.stop();
    }
  }
}
