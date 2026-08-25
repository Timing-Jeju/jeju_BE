package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SavedPlacesMigrationIntegrationTest {
  private static final String TARGET = "20260903000000_saved_places_api.sql";
  private static PostgreSQLContainer container;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void startAtPreviousSchemaThenApplyTarget() throws Exception {
    container = PostgreSqlTestContainerFactory.createBefore(TARGET);
    container.start();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    legacyRows();
    Path target =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("supabase/migrations")
            .resolve(TARGET);
    PostgreSqlTestContainerFactory.executeScript(container, target);
  }

  @AfterAll
  static void stop() {
    if (container != null) container.stop();
  }

  @Test
  @Order(1)
  void legacy_overlong_control_blank_NFD값은_audit에_원본을_보존하고_live를_canonicalize한다() throws Exception {
    var live =
        jdbc.queryForMap(
            "select memo,tags,priority,target_day from public.saved_places where id='34200000-0000-0000-0000-000000000021'");
    assertThat((String) live.get("memo")).startsWith("\u2003동쪽").hasSize(2000).doesNotContain("\n");
    assertThat((String[]) ((java.sql.Array) live.get("tags")).getArray())
        .containsExactly("동쪽", "필수");
    assertThat(live.get("priority")).isEqualTo(5);
    assertThat(live.get("target_day")).isEqualTo(365);

    var audit =
        jdbc.queryForMap(
            "select original_memo,original_tags,reasons from public.saved_places_backfill_audit where saved_place_id='34200000-0000-0000-0000-000000000021'");
    assertThat((String) audit.get("original_memo"))
        .startsWith(" \u2003동쪽")
        .contains("\n")
        .hasSizeGreaterThan(2000);
    assertThat((String[]) ((java.sql.Array) audit.get("original_tags")).getArray())
        .contains("동쪽", "", "필수");
    assertThat((String[]) ((java.sql.Array) audit.get("reasons")).getArray())
        .containsExactlyInAnyOrder(
            "priority_out_of_range",
            "target_day_out_of_range",
            "memo_noncanonical",
            "tags_noncanonical");

    var dualLive =
        jdbc.queryForMap(
            "select user_id,session_id from public.saved_places where id='34200000-0000-0000-0000-000000000022'");
    assertThat(dualLive.get("user_id"))
        .isEqualTo(UUID.fromString("34200000-0000-0000-0000-000000000001"));
    assertThat(dualLive.get("session_id")).isNull();
    var dualAudit =
        jdbc.queryForMap(
            "select user_id,session_id,reasons from public.saved_places_backfill_audit where saved_place_id='34200000-0000-0000-0000-000000000022'");
    assertThat(dualAudit.get("user_id"))
        .isEqualTo(UUID.fromString("34200000-0000-0000-0000-000000000001"));
    assertThat(dualAudit.get("session_id"))
        .isEqualTo(UUID.fromString("34200000-0000-0000-0000-000000000002"));
    assertThat((String[]) ((java.sql.Array) dualAudit.get("reasons")).getArray())
        .contains("legacy_dual_owner");
  }

  @Test
  @Order(2)
  void migration후_negative_constraints와_hard_delete_cascade가_실제_PostgreSQL에서_동작한다() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.saved_places set session_id='34200000-0000-0000-0000-000000000002' where id='34200000-0000-0000-0000-000000000021'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.saved_places set priority=6,tags=array['필수','동쪽'] where id='34200000-0000-0000-0000-000000000021'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "insert into public.saved_place_idempotency(owner_sub,idempotency_key,request_hash,place_id,created,response_etag,response_name,response_category,response_tags,response_priority,response_saved_at,response_updated_at,expires_at) values ('34200000-0000-0000-0000-000000000001','hard-delete',repeat('a',64),'34200000-0000-0000-0000-000000000011',true,'\"etag\"','장소','VE',array['동쪽'],0,now(),now(),now()+interval '24 hours')");
    jdbc.update("delete from public.tour_places where id='34200000-0000-0000-0000-000000000011'");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_place_idempotency where idempotency_key='hard-delete'",
                Integer.class))
        .isOne();
    jdbc.update("delete from auth.users where id='34200000-0000-0000-0000-000000000001'");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_place_idempotency where idempotency_key='hard-delete'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_places_backfill_audit where saved_place_id='34200000-0000-0000-0000-000000000021'",
                Integer.class))
        .isZero();
  }

  @Test
  @Order(3)
  void audit_user와_session_FK는_각각_leading_index를_가진다() {
    assertThat(
            jdbc.queryForList(
                """
                select constraint_row.conname
                from pg_catalog.pg_constraint constraint_row
                where constraint_row.conrelid = 'public.saved_places_backfill_audit'::regclass
                  and constraint_row.contype = 'f'
                  and exists (
                    select 1 from pg_catalog.pg_index index_row
                    where index_row.indrelid = constraint_row.conrelid
                      and (index_row.indkey::smallint[])[0:cardinality(constraint_row.conkey)-1]
                          = constraint_row.conkey
                  )
                order by constraint_row.conname
                """,
                String.class))
        .containsExactly(
            "saved_places_backfill_audit_session_id_fkey",
            "saved_places_backfill_audit_user_id_fkey");
  }

  private static void legacyRows() {
    jdbc.update(
        "insert into auth.users(id,email) values ('34200000-0000-0000-0000-000000000001','legacy@example.test')");
    jdbc.update(
        "insert into public.user_profiles(id,email) values ('34200000-0000-0000-0000-000000000001','legacy@example.test')");
    jdbc.update(
        "insert into public.app_sessions(id,user_id,public_token) values ('34200000-0000-0000-0000-000000000002','34200000-0000-0000-0000-000000000001','legacy-dual-owner')");
    jdbc.update(
        """
        insert into public.tour_places(id,content_id,name,normalized_name,category,region_code,
          location,source_provider,source_service)
        values ('34200000-0000-0000-0000-000000000011','legacy-place','장소','장소','VE','seongsan',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture','migration-test')
        """);
    jdbc.update(
        """
        insert into public.tour_places(id,content_id,name,normalized_name,category,region_code,
          location,source_provider,source_service)
        values ('34200000-0000-0000-0000-000000000012','legacy-dual-place','장소2','장소2','VE','seongsan',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture','migration-test')
        """);
    jdbc.update(
        """
        insert into public.saved_places(id,user_id,place_id,memo,tags,target_day,priority)
        values ('34200000-0000-0000-0000-000000000021',
          '34200000-0000-0000-0000-000000000001',
          '34200000-0000-0000-0000-000000000011',?,array['필수','동쪽',''],999,10)
        """,
        " \u2003동쪽" + "메".repeat(2001) + "\n ");
    jdbc.update(
        """
        insert into public.saved_places(id,user_id,session_id,place_id,memo,tags,target_day,priority)
        values ('34200000-0000-0000-0000-000000000022',
          '34200000-0000-0000-0000-000000000001',
          '34200000-0000-0000-0000-000000000002',
          '34200000-0000-0000-0000-000000000012','dual owner',array['동쪽'],1,0)
        """);
  }
}
