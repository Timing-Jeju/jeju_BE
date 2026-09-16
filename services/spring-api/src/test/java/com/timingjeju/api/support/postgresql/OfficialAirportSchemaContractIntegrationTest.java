package com.timingjeju.api.support.postgresql;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class OfficialAirportSchemaContractIntegrationTest {
  @Test
  void 공항_binding을_포함한_전체_Docker_DB계약이_외래키인덱스와_무결성을_검증한다() throws Exception {
    Path root = Path.of("../..").toAbsolutePath().normalize();
    try (var container = PostgreSqlTestContainerFactory.create()) {
      container.start();
      for (String script :
          List.of(
              "db/local-postgres/seed_fixtures.sql",
              "db/queries/schema_contract.sql",
              "db/queries/database_negative_constraints.sql",
              "db/queries/smoke_check.sql",
              "db/queries/private_trip_ownership_helper_contract.sql")) {
        PostgreSqlTestContainerFactory.executeScript(container, root.resolve(script));
      }
    }
  }
}
