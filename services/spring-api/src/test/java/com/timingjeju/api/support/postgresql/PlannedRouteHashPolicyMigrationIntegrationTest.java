package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PlannedRouteHashPolicyMigrationIntegrationTest {
  private static final String LEGACY = "20260918000015_planned_route_snapshot_provenance.sql";
  private static final String REFERENCES = "20260918000016_planned_route_reference_integrity.sql";
  private static final String TARGET = "20260918000019_planned_route_request_hash_policy.sql";
  private static final String HASH_FUNCTION =
      "timing_jeju_planner_private.planned_route_request_hash(public.mobility_route_snapshots)";
  private static final String ROUTES = "public.mobility_route_snapshots";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void legacy015_016은_hash만_교체하고_rollback_reapply_fresh_schema_ACL이_일치한다(String image)
      throws Exception {
    String upgradedSchema;
    try (var container = legacyContainer(image)) {
      var jdbc = jdbc(container);
      String oldHash = jdbc.queryForObject("select request_hash from " + ROUTES, String.class);
      String beforeData = dataWithoutHash(jdbc);
      String beforeSchema = schema(jdbc);
      var beforeProtection = protections(jdbc);
      assertThat(functionDefinition(jdbc)).contains("st_asewkt");
      assertThat(oldHash).isNotEqualTo(expectedHash(jdbc));
      assertThat(
              hashWith(
                  jdbc,
                  "jsonb_build_object('origin_location',ST_GeogFromText('SRID=4326;POINT(126.6"
                      + " 33.6)'))"))
          .isNotEqualTo(oldHash);

      // Force a failure after the guarded UPDATE: neither new hash, function nor trigger state
      // leaks.
      String migration = Files.readString(path(TARGET));
      assertThatThrownBy(
              () -> {
                try (var connection = jdbc.getDataSource().getConnection();
                    var statement = connection.createStatement()) {
                  statement.execute(migration.replace("commit;", "select 1 / 0; commit;"));
                }
              })
          .hasMessageContaining("division by zero");
      assertThat(schema(jdbc)).isEqualTo(beforeSchema);
      assertThat(dataWithoutHash(jdbc)).isEqualTo(beforeData);
      assertThat(jdbc.queryForObject("select request_hash from " + ROUTES, String.class))
          .isEqualTo(oldHash);

      PostgreSqlTestContainerFactory.executeScript(container, path(TARGET));
      assertThat(dataWithoutHash(jdbc)).isEqualTo(beforeData);
      assertThat(protections(jdbc)).isEqualTo(beforeProtection);
      assertThat(protections(jdbc).getFirst())
          .containsEntry("prosecdef", false)
          .containsEntry("provolatile", "i")
          .containsEntry("anon_exec", false)
          .containsEntry("authenticated_exec", false)
          .containsEntry("service_exec", true);
      assertThat(jdbc.queryForObject("select request_hash from " + ROUTES, String.class))
          .isEqualTo(expectedHash(jdbc))
          .isNotEqualTo(oldHash);
      assertExactIdentityAndIndependence(jdbc);
      assertThatThrownBy(() -> jdbc.update("update " + ROUTES + " set request_hash=repeat('0',64)"))
          .hasMessageContaining("planned route snapshots are immutable");
      assertThatThrownBy(() -> jdbc.update("update " + ROUTES + " set duration_minutes=11"))
          .hasMessageContaining("planned route snapshots are immutable");
      upgradedSchema = schema(jdbc);
      String stableRows =
          jdbc.queryForObject(
              "select jsonb_agg(snapshot)::text from " + ROUTES + " snapshot", String.class);
      PostgreSqlTestContainerFactory.executeScript(container, path(TARGET));
      assertThat(schema(jdbc)).isEqualTo(upgradedSchema);
      assertThat(
              jdbc.queryForObject(
                  "select jsonb_agg(snapshot)::text from " + ROUTES + " snapshot", String.class))
          .isEqualTo(stableRows);
    }
    try (var fresh = PostgreSqlTestContainerFactory.createBefore(TARGET, image)) {
      fresh.start();
      PostgreSqlTestContainerFactory.executeScript(fresh, path(TARGET));
      assertThat(schema(jdbc(fresh))).isEqualTo(upgradedSchema);
      PostgreSqlTestContainerFactory.executeScript(
          fresh,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("db/local-postgres/seed_fixtures.sql"));
      assertThat(
              jdbc(fresh)
                  .queryForObject(
                      "select count(*) from "
                          + ROUTES
                          + " snapshot where request_hash <>"
                          + " timing_jeju_planner_private.planned_route_request_hash(snapshot)",
                      Integer.class))
          .isZero();
      assertThat(jdbc(fresh).queryForObject("select count(*) from " + ROUTES, Integer.class))
          .isEqualTo(4);
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 축소_identity_중복은_원문_hash_노출이나_병합없이_전체_rollback한다(String image) throws Exception {
    try (var container = legacyContainer(image)) {
      var jdbc = jdbc(container);
      jdbc.update(
          """
          insert into public.mobility_route_snapshots
            (trip_plan_id,schedule_version_id,origin_item_id,destination_item_id,
             origin_anchor_kind,origin_anchor_id,destination_anchor_kind,destination_anchor_id,
             transport_mode,departure_at,duration_minutes,source_provider,source_operation,expires_at)
          select trip_plan_id,schedule_version_id,origin_item_id,destination_item_id,
                 origin_anchor_kind,origin_anchor_id,destination_anchor_kind,destination_anchor_id,
                 transport_mode,departure_at+interval '1 minute',duration_minutes,
                 source_provider,source_operation,expires_at
          from public.mobility_route_snapshots
          """);
      String beforeSchema = schema(jdbc);
      String beforeData = dataWithoutHash(jdbc);
      var hashes =
          jdbc.queryForList("select request_hash from " + ROUTES + " order by id", String.class);
      assertThat(hashes).hasSize(2).doesNotHaveDuplicates();
      assertThatThrownBy(
              () -> PostgreSqlTestContainerFactory.executeScript(container, path(TARGET)))
          .hasMessageContaining("planned route hash policy requires duplicate identity audit")
          .hasMessageNotContaining(hashes.get(0))
          .hasMessageNotContaining(hashes.get(1));
      assertThat(schema(jdbc)).isEqualTo(beforeSchema);
      assertThat(dataWithoutHash(jdbc)).isEqualTo(beforeData);
      assertThat(
              jdbc.queryForList(
                  "select request_hash from " + ROUTES + " order by id", String.class))
          .isEqualTo(hashes);
    }
  }

  private static void assertExactIdentityAndIndependence(JdbcTemplate jdbc) throws Exception {
    String expected = expectedHash(jdbc);
    assertThat(functionDefinition(jdbc))
        .doesNotContain("st_asewkt", "origin_location", "destination_location");
    // The independent Java SHA-256 oracle uses UTF-8 byte lengths, not SQL's hash helper.
    for (String excluded :
        List.of(
            "owner_user_id",
            "origin_item_id",
            "destination_item_id",
            "origin_source_place_id",
            "destination_source_place_id",
            "origin_source_stop_id",
            "destination_source_stop_id")) {
      assertThat(
              hashWith(
                  jdbc,
                  "jsonb_build_object('" + excluded + "','00000000-0000-0000-0000-000000000099')"))
          .as(excluded)
          .isEqualTo(expected);
    }
    for (String overrides :
        List.of(
            "jsonb_build_object('origin_location',ST_GeogFromText('SRID=4326;POINT(126.6 33.6)'))",
            "jsonb_build_object('destination_location',ST_GeogFromText('SRID=4326;POINT(126.7"
                + " 33.7)'))",
            "'{\"transport_mode\":\"taxi\",\"departure_at\":\"2026-09-01T02:00:00Z\",\"source_provider\":\"other\",\"source_operation\":\"other\"}'::jsonb")) {
      assertThat(hashWith(jdbc, overrides)).isEqualTo(expected);
    }
    for (String field :
        List.of(
            "trip_plan_id", "schedule_version_id", "origin_anchor_id", "destination_anchor_id")) {
      assertThat(
              hashWith(
                  jdbc,
                  "jsonb_build_object('" + field + "','00000000-0000-0000-0000-000000000099')"))
          .as(field)
          .isNotEqualTo(expected);
    }
    for (String field :
        List.of("anchor_contract_version", "origin_anchor_kind", "destination_anchor_kind")) {
      assertThat(hashWith(jdbc, "jsonb_build_object('" + field + "','다른:계약')"))
          .as(field)
          .isNotEqualTo(expected);
    }
    assertThat(
            hashWith(
                jdbc,
                "jsonb_build_object('origin_anchor_kind',snapshot.destination_anchor_kind,"
                    + "'origin_anchor_id',snapshot.destination_anchor_id,'destination_anchor_kind',snapshot.origin_anchor_kind,"
                    + "'destination_anchor_id',snapshot.origin_anchor_id)"))
        .isNotEqualTo(expected);
    // Public source movement does not enter the identity function; the separate sealing guard still
    // rejects stale cache geometry.
    jdbc.update(
        "update public.tour_places set location=ST_GeogFromText('SRID=4326;POINT(126.8 33.8)')"
            + " where id=(select origin_anchor_id from "
            + ROUTES
            + ")");
    assertThat(
            hashWith(
                jdbc,
                "jsonb_build_object('origin_location',(select location from public.tour_places"
                    + " where id=snapshot.origin_anchor_id))"))
        .isEqualTo(expected);
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "select"
                        + " public.assert_schedule_version_sealable(schedule_version_id,trip_plan_id)"
                        + " from "
                        + ROUTES))
        .hasMessageContaining("planned route anchor lineage does not match");
  }

  private static String hashWith(JdbcTemplate jdbc, String overrides) {
    return jdbc.queryForObject(
        "select"
            + " timing_jeju_planner_private.planned_route_request_hash(jsonb_populate_record(snapshot,"
            + overrides
            + ")) from "
            + ROUTES
            + " snapshot",
        String.class);
  }

  private static String expectedHash(JdbcTemplate jdbc) throws Exception {
    var components =
        jdbc.queryForObject(
            """
            select anchor_contract_version,trip_plan_id,schedule_version_id,origin_anchor_kind,
                   origin_anchor_id,destination_anchor_kind,destination_anchor_id
            from public.mobility_route_snapshots
            """,
            (rs, index) -> {
              var values = new java.util.ArrayList<String>();
              for (int column = 1; column <= 7; column++) values.add(rs.getString(column));
              return values;
            });
    var input = new StringBuilder();
    for (String component : components)
      input.append(component.getBytes(StandardCharsets.UTF_8).length).append(':').append(component);
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private static PostgreSQLContainer legacyContainer(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(LEGACY, image);
    try {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(
          container,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("db/local-postgres/legacy_planned_route_provenance_fixture.sql"));
      PostgreSqlTestContainerFactory.executeScript(container, path(LEGACY));
      PostgreSqlTestContainerFactory.executeScript(container, path(REFERENCES));
      PostgreSqlTestContainerFactory.executeScript(
          container,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("db/local-postgres/20260918000017_location_cutover_group.sql"));
      return container;
    } catch (Exception failure) {
      container.stop();
      throw failure;
    }
  }

  private static java.nio.file.Path path(String migration) {
    return PostgreSqlTestContainerFactory.canonicalMigrationPath(
        PostgreSqlTestContainerFactory.locateRepositoryRoot(), migration);
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer container) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }

  private static String functionDefinition(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "select lower(pg_get_functiondef(?::regprocedure))", String.class, HASH_FUNCTION);
  }

  private static List<java.util.Map<String, Object>> protections(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        """
        select oid,proowner,proacl::text,proconfig::text,prosecdef,provolatile,
          has_function_privilege('anon',oid,'execute') as anon_exec,
          has_function_privilege('authenticated',oid,'execute') as authenticated_exec,
          has_function_privilege('service_role',oid,'execute') as service_exec,
          (select string_agg(tgname::text||':'||tgenabled::text,',' order by tgname)
           from pg_trigger where tgrelid='public.mobility_route_snapshots'::regclass) as triggers
        from pg_proc where oid=?::regprocedure
        """,
        HASH_FUNCTION);
  }

  private static String schema(JdbcTemplate jdbc) throws Exception {
    return jdbc.queryForObject(
        Files.readString(
            PostgreSqlTestContainerFactory.locateRepositoryRoot()
                .resolve("db/queries/canonical_migration_fingerprint.sql")),
        String.class);
  }

  private static String dataWithoutHash(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        """
        select jsonb_build_object(
          'routes',(select jsonb_agg(to_jsonb(s)-'request_hash' order by id) from public.mobility_route_snapshots s),
          'legs',(select jsonb_agg(s order by id) from public.trip_legs s),
          'items',(select jsonb_agg(s order by id) from public.trip_items s),
          'versions',(select jsonb_agg(s order by id) from public.trip_schedule_versions s),
          'trips',(select jsonb_agg(s order by id) from public.trip_plans s))::text
        """,
        String.class);
  }
}
