package com.timingjeju.api.support.postgresql;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class PlannedRouteReferenceIndexIntegrationTest {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 후속_index_migration은_계획_route_FK와_전체_스키마_계약을_만족한다(String image) throws Exception {
    String migration = "20260918000016_planned_route_reference_integrity.sql";
    var container = PostgreSqlTestContainerFactory.createBefore(migration, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("supabase/migrations").resolve(migration));
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("db/queries/schema_contract.sql"));
    } finally {
      container.stop();
    }
  }
}
