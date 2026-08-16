package com.timingjeju.api.global.staypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.RecommendedStaySource;
import com.timingjeju.api.application.staypolicy.StayPolicyCandidate;
import com.timingjeju.api.application.staypolicy.StayPolicyPublicationStore;
import com.timingjeju.api.application.staypolicy.StayPolicyResolver;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetCatalog;
import com.timingjeju.api.application.staypolicy.ValidatedStayPolicyPayload;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcStayPolicyRepositoryIntegrationTest {

  private static final UUID OVERRIDE_PLACE =
      UUID.fromString("65000000-0000-0000-0000-000000000001");
  private static final UUID CATEGORY_PLACE =
      UUID.fromString("65000000-0000-0000-0000-000000000002");
  private static final UUID UNAVAILABLE_PLACE =
      UUID.fromString("65000000-0000-0000-0000-000000000003");
  private static final Instant V1_EFFECTIVE = Instant.parse("2026-08-22T09:00:00Z");
  private static final Instant V2_EFFECTIVE = Instant.parse("2026-08-23T09:00:00Z");
  private static final Instant IMPORTED = Instant.parse("2026-08-23T09:00:05Z");

  @Autowired private StayPolicyTargetCatalog catalog;
  @Autowired private StayPolicyPublicationStore store;
  @Autowired private StayPolicyResolver resolver;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUp() {
    clean();
    insertPlace(OVERRIDE_PLACE, "VE", false, null, 77);
    insertPlace(CATEGORY_PLACE, "VE", false, null, 66);
    insertPlace(UNAVAILABLE_PLACE, "content-type:99", false, null, 55);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void 원자교체후_단일_resolver가_override_category_unavailable과_provenance를_반환한다() {
    store.publish(payload("v1", null, V1_EFFECTIVE, List.of(category("VE", 80))), IMPORTED);
    store.publish(
        payload(
            "v2", "v1", V2_EFFECTIVE, List.of(category("VE", 90), override(OVERRIDE_PLACE, 120))),
        IMPORTED.plusSeconds(1));

    RecommendedStay overridden = resolver.resolve(OVERRIDE_PLACE, "VE");
    RecommendedStay category = resolver.resolve(CATEGORY_PLACE, "VE");

    assertThat(overridden)
        .isEqualTo(
            new RecommendedStay(
                120,
                RecommendedStaySource.PLACE_OVERRIDE,
                "v2",
                V2_EFFECTIVE,
                IMPORTED.plusSeconds(1)));
    assertThat(category.source()).isEqualTo(RecommendedStaySource.CATEGORY_DEFAULT);
    assertThat(category.minutes()).isEqualTo(90);
    assertThat(resolver.resolve(UNAVAILABLE_PLACE, "content-type:99"))
        .isEqualTo(RecommendedStay.unavailable());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.place_stay_policy_versions where status='active'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select status from public.place_stay_policy_versions where version='v1'",
                String.class))
        .isEqualTo("retired");
  }

  @Test
  void stale_CAS는_새_version_row없이_실패하고_이전_active를_유지한다() {
    store.publish(payload("v1", null, V1_EFFECTIVE, List.of(category("VE", 80))), IMPORTED);

    assertThatThrownBy(
            () ->
                store.publish(
                    payload("v2", "stale", V2_EFFECTIVE, List.of(category("VE", 90))),
                    IMPORTED.plusSeconds(1)))
        .isInstanceOf(StayPolicyPublicationException.class)
        .hasMessageContaining("expected active version");

    assertThat(
            jdbc.queryForList(
                "select version from public.place_stay_policy_versions order by version",
                String.class))
        .containsExactly("v1");
    assertThat(resolver.resolve(CATEGORY_PLACE, "VE").minutes()).isEqualTo(80);
  }

  @Test
  void target_catalog는_live_category와_place만_인정하고_publish는_tour_place_lineage를_변경하지_않는다() {
    UUID stale = UUID.fromString("65000000-0000-0000-0000-000000000004");
    UUID tombstoned = UUID.fromString("65000000-0000-0000-0000-000000000005");
    insertPlace(stale, "STALE", true, null, 44);
    insertPlace(tombstoned, "DELETED", false, IMPORTED, 33);

    var targets =
        catalog.validateTargets(
            Set.of("VE", "STALE", "DELETED"), Set.of(OVERRIDE_PLACE, stale, tombstoned));
    String before = placeFingerprint(OVERRIDE_PLACE);
    store.publish(
        payload("v1", null, V1_EFFECTIVE, List.of(override(OVERRIDE_PLACE, 120))), IMPORTED);

    assertThat(targets.liveCategories()).containsExactly("VE");
    assertThat(targets.livePlaceIds()).containsExactly(OVERRIDE_PLACE);
    assertThat(placeFingerprint(OVERRIDE_PLACE)).isEqualTo(before);
  }

  @Test
  void DB는_scope_XOR_minutes_hash_version_single_active를_방어한다() {
    insertVersion("v1", "active");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into public.place_stay_policies(version,scope,category,place_id,minutes) values ('v1','category_default','VE',?,90)",
                    OVERRIDE_PLACE))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into public.place_stay_policies(version,scope,category,minutes) values ('v1','category_default','VE',1)"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertVersion("v2", "active"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 같은_expected_active의_동시_publish는_하나만_성공하고_하나는_stable_conflict가_된다() throws Exception {
    store.publish(payload("v1", null, V1_EFFECTIVE, List.of(category("VE", 80))), IMPORTED);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> failures = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<?> first =
          executor.submit(() -> publishConcurrently("v2", 90, ready, start, failures));
      Future<?> second =
          executor.submit(() -> publishConcurrently("v3", 100, ready, start, failures));
      ready.await();
      start.countDown();
      first.get();
      second.get();
    }

    assertThat(failures)
        .singleElement()
        .satisfies(
            failure -> {
              assertThat(failure).isInstanceOf(StayPolicyPublicationException.class);
              assertThat(failure.getMessage()).contains("expected active version v1");
            });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.place_stay_policy_versions where status='active'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select version from public.place_stay_policy_versions where status='active'",
                String.class))
        .isIn("v2", "v3");
  }

  @Test
  void 같은_version_hash_replay는_noop이고_같은_version의_다른_hash는_collision으로_거부한다() {
    var initial = payload("v1", null, V1_EFFECTIVE, List.of(category("VE", 80)));
    store.publish(initial, IMPORTED);

    store.publish(
        payload("v1", "v1", V1_EFFECTIVE, List.of(category("VE", 80))), IMPORTED.plusSeconds(1));
    assertThatThrownBy(
            () ->
                store.publish(
                    new ValidatedStayPolicyPayload(
                        "v1",
                        V1_EFFECTIVE,
                        "v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        List.of(category("VE", 90))),
                    IMPORTED.plusSeconds(2)))
        .isInstanceOf(StayPolicyPublicationException.class)
        .hasMessageContaining("payload hash collision");

    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.place_stay_policy_versions", Integer.class))
        .isEqualTo(1);
    assertThat(resolver.resolve(CATEGORY_PLACE, "VE").minutes()).isEqualTo(80);
  }

  @Test
  void policy_insert_실패는_새_version과_이전_active_retirement를_모두_rollback한다() {
    store.publish(payload("v1", null, V1_EFFECTIVE, List.of(category("VE", 80))), IMPORTED);
    StayPolicyCandidate invalid =
        new StayPolicyCandidate(
            com.timingjeju.api.application.staypolicy.StayPolicyScope.CATEGORY_DEFAULT,
            "VE",
            OVERRIDE_PLACE,
            90);

    assertThatThrownBy(
            () ->
                store.publish(
                    payload("v2", "v1", V2_EFFECTIVE, List.of(invalid)), IMPORTED.plusSeconds(1)))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            jdbc.queryForList(
                "select version || ':' || status from public.place_stay_policy_versions",
                String.class))
        .containsExactly("v1:active");
  }

  @Test
  void active_read는_writer의_미커밋_교체를_섞지_않고_commit전_v1_commit후_v2를_읽는다() throws Exception {
    store.publish(payload("v1", null, V1_EFFECTIVE, List.of(category("VE", 80))), IMPORTED);
    try (Connection writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try (PreparedStatement retire =
              writer.prepareStatement(
                  "update public.place_stay_policy_versions set status='retired' where version='v1'");
          PreparedStatement version =
              writer.prepareStatement(
                  "insert into public.place_stay_policy_versions(version,status,payload_hash,effective_at,imported_at) values ('v2','active',repeat('66',32),?,?)");
          PreparedStatement policy =
              writer.prepareStatement(
                  "insert into public.place_stay_policies(version,scope,category,minutes,updated_at) values ('v2','category_default','VE',90,?)")) {
        retire.executeUpdate();
        version.setTimestamp(1, Timestamp.from(V2_EFFECTIVE));
        version.setTimestamp(2, Timestamp.from(IMPORTED.plusSeconds(1)));
        version.executeUpdate();
        policy.setTimestamp(1, Timestamp.from(IMPORTED.plusSeconds(1)));
        policy.executeUpdate();

        assertThat(resolver.resolve(CATEGORY_PLACE, "VE").policyVersion()).isEqualTo("v1");
        writer.commit();
      }
    }
    assertThat(resolver.resolve(CATEGORY_PLACE, "VE").policyVersion()).isEqualTo("v2");
  }

  @Test
  void RLS_ACL_FK_index와_active_resolver_query_plan을_고정한다() {
    assertThat(
            jdbc.queryForObject(
                "select relrowsecurity from pg_class where oid='public.place_stay_policies'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege('anon','public.place_stay_policies','SELECT')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege('authenticated','public.place_stay_policies','SELECT')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege('service_role','public.place_stay_policies','SELECT,INSERT')",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select confdeltype::text from pg_constraint where conrelid='public.place_stay_policies'::regclass and confrelid='public.tour_places'::regclass",
                String.class))
        .isEqualTo("r");
    assertThat(
            jdbc.queryForList(
                "select indexname from pg_indexes where schemaname='public' and tablename='place_stay_policies'",
                String.class))
        .contains(
            "uq_place_stay_policy_category",
            "uq_place_stay_policy_place",
            "idx_place_stay_policy_place_lookup",
            "idx_place_stay_policy_category_lookup");

    store.publish(payload("v1", null, V1_EFFECTIVE, List.of(category("VE", 80))), IMPORTED);
    jdbc.execute("set enable_seqscan=off");
    String plan =
        String.join(
            "\n",
            jdbc.queryForList(
                "explain select p.minutes from public.place_stay_policy_versions v join public.place_stay_policies p on p.version=v.version where v.status='active' and ((p.scope='place_override' and p.place_id='65000000-0000-0000-0000-000000000001') or (p.scope='category_default' and p.category='VE')) order by case p.scope when 'place_override' then 0 else 1 end limit 1",
                String.class));
    jdbc.execute("reset enable_seqscan");
    assertThat(plan).contains("place_stay_policy");
  }

  private void publishConcurrently(
      String version,
      int minutes,
      CountDownLatch ready,
      CountDownLatch start,
      List<Throwable> failures) {
    ready.countDown();
    try {
      start.await();
      store.publish(
          payload(version, "v1", V2_EFFECTIVE, List.of(category("VE", minutes))),
          IMPORTED.plusSeconds(1));
    } catch (Throwable failure) {
      synchronized (failures) {
        failures.add(failure);
      }
    }
  }

  private ValidatedStayPolicyPayload payload(
      String version, String expected, Instant effective, List<StayPolicyCandidate> policies) {
    return new ValidatedStayPolicyPayload(
        version,
        effective,
        expected,
        "6565656565656565656565656565656565656565656565656565656565656565",
        policies);
  }

  private static StayPolicyCandidate category(String category, int minutes) {
    return StayPolicyCandidate.categoryDefault(category, minutes);
  }

  private static StayPolicyCandidate override(UUID placeId, int minutes) {
    return StayPolicyCandidate.placeOverride(placeId, minutes);
  }

  private void insertVersion(String version, String status) {
    jdbc.update(
        "insert into public.place_stay_policy_versions(version,status,payload_hash,effective_at,imported_at) values (?,?,repeat('65',32),?,?)",
        version,
        status,
        Timestamp.from(V1_EFFECTIVE),
        Timestamp.from(IMPORTED));
  }

  private void insertPlace(
      UUID id, String category, boolean stale, Instant sourceDeletedAt, Integer legacyMinutes) {
    jdbc.update(
        """
        insert into public.tour_places (
          id, name, normalized_name, category, location, recommended_stay_minutes,
          source_provider, source_service, stale, source_deleted_at, updated_at
        ) values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,
                  ?, 'fixture', 'stay-policy-test', ?, ?, ?)
        """,
        id,
        "place-" + id,
        "place-" + id,
        category,
        legacyMinutes,
        stale,
        sourceDeletedAt == null ? null : Timestamp.from(sourceDeletedAt),
        Timestamp.from(V1_EFFECTIVE));
  }

  private String placeFingerprint(UUID placeId) {
    return jdbc.queryForObject(
        "select concat_ws('|',xmin::text,recommended_stay_minutes,source_provider,source_service,coalesce(import_run_id::text,''),coalesce(source_snapshot_id::text,''),updated_at::text) from public.tour_places where id=?",
        String.class,
        placeId);
  }

  private void clean() {
    jdbc.update("delete from public.place_stay_policies");
    jdbc.update("delete from public.place_stay_policy_versions");
    jdbc.update("delete from public.tour_api_operation_provenance");
    jdbc.update("delete from public.tour_place_sources");
    jdbc.update("delete from public.tour_places");
    jdbc.update("delete from public.external_api_snapshots");
    jdbc.update("delete from public.data_import_runs");
  }
}
