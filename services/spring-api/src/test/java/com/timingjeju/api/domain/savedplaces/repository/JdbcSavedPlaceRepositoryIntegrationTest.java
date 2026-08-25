package com.timingjeju.api.domain.savedplaces.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.savedplaces.dto.PatchSavedPlaceRequest;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesQuery;
import com.timingjeju.api.domain.savedplaces.service.SavedPlaceService;
import com.timingjeju.api.global.retention.SavedPlaceRetentionScheduler;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "app.saved-place-retention.enabled=true")
class JdbcSavedPlaceRepositoryIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID USER_A = UUID.fromString("34000000-0000-0000-0000-000000000001");
  private static final UUID USER_B = UUID.fromString("34000000-0000-0000-0000-000000000002");
  private static final UUID PLACE_A = UUID.fromString("34000000-0000-0000-0000-000000000011");
  private static final UUID PLACE_B = UUID.fromString("34000000-0000-0000-0000-000000000012");

  @Autowired private JdbcSavedPlaceRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private SavedPlaceService service;
  @Autowired private SavedPlaceRetentionScheduler retentionScheduler;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from auth.users where id in (?,?)", USER_A, USER_B);
    jdbc.update("delete from public.tour_places where id in (?,?)", PLACE_A, PLACE_B);
    user(USER_A);
    user(USER_B);
    place(PLACE_A, "VE", "seongsan", 60);
    place(PLACE_B, "FD", "jeju-si", null);
  }

  @Test
  void POST는_첫_201과_same_key_replay와_payload_conflict를_24시간_registry로_보장한다() {
    var command = SavedPlaceCommand.create(PLACE_A, " 오전 ", List.of("필수", "동쪽"), 5, 1);

    var first = repository.create(USER_A, "saved-place-key-001", command);
    var replay = repository.create(USER_A, "saved-place-key-001", command);

    assertThat(first.created()).isTrue();
    assertThat(first.replayed()).isFalse();
    assertThat(replay.created()).isTrue();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.place()).isEqualTo(first.place());
    assertThat(replay.etag()).isEqualTo(first.etag());
    PatchSavedPlaceRequest change = new PatchSavedPlaceRequest();
    change.setMemo("변경 후 삭제");
    repository.patch(USER_A, PLACE_A, first.etag(), change.toCommand());
    repository.delete(USER_A, PLACE_A);
    var replayAfterMutation = repository.create(USER_A, "saved-place-key-001", command);
    assertThat(replayAfterMutation.place()).isEqualTo(first.place());
    assertThat(replayAfterMutation.etag()).isEqualTo(first.etag());
    assertThatThrownBy(
            () ->
                repository.create(
                    USER_A,
                    "saved-place-key-001",
                    SavedPlaceCommand.create(PLACE_A, "다른 값", List.of(), 0, null)))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT");
  }

  @Test
  void 다른_key의_same_payload는_200_replay이고_different_payload는_conflict다() {
    var command = SavedPlaceCommand.create(PLACE_A, null, null, null, null);
    repository.create(USER_A, "saved-place-key-101", command);

    var existing = repository.create(USER_A, "saved-place-key-102", command);

    assertThat(existing.created()).isFalse();
    assertThat(existing.replayed()).isTrue();
    assertThatThrownBy(
            () ->
                repository.create(
                    USER_A,
                    "saved-place-key-103",
                    SavedPlaceCommand.create(PLACE_A, "변경", null, null, null)))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("SAVED_PLACE_ALREADY_EXISTS");
  }

  @Test
  void same_key의_memo_null과_literal_null은_idempotency_payload_conflict다() {
    repository.create(
        USER_A,
        "memo-null-conflict",
        SavedPlaceCommand.create(PLACE_A, null, List.of("동쪽"), 1, null));

    assertThatThrownBy(
            () ->
                repository.create(
                    USER_A,
                    "memo-null-conflict",
                    SavedPlaceCommand.create(PLACE_A, "null", List.of("동쪽"), 1, null)))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT");
  }

  @Test
  void PATCH는_omitted_null_replace와_stale_ETag_compare_update를_원자적으로_처리한다() {
    var created =
        repository.create(
            USER_A,
            "saved-place-key-201",
            SavedPlaceCommand.create(PLACE_A, "메모", List.of("동쪽", "필수"), 5, 1));
    PatchSavedPlaceRequest request = new PatchSavedPlaceRequest();
    request.setMemo(null);
    request.setTags(List.of("동쪽"));
    request.setPriority(null);
    request.setTargetDay(2);
    var patch = request.toCommand();

    var updated = repository.patch(USER_A, PLACE_A, created.etag(), patch);

    assertThat(updated.place().memo()).isNull();
    assertThat(updated.place().tags()).containsExactly("동쪽");
    assertThat(updated.place().priority()).isZero();
    assertThat(updated.place().targetDay()).isEqualTo(2);
    assertThat(updated.etag()).isNotEqualTo(created.etag());
    assertThatThrownBy(() -> repository.patch(USER_A, PLACE_A, created.etag(), patch))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("SAVED_PLACE_VERSION_CONFLICT");
  }

  @Test
  void GET_cursor는_owner_filter_sort_size_scope를_서명하고_stable_tie로_진행한다() {
    repository.create(
        USER_A,
        "saved-place-key-301",
        SavedPlaceCommand.create(PLACE_A, null, List.of("동쪽"), 5, null));
    repository.create(
        USER_A,
        "saved-place-key-302",
        SavedPlaceCommand.create(PLACE_B, null, List.of("동쪽"), 5, null));
    SavedPlacesQuery firstQuery = SavedPlacesQuery.of("동쪽", null, null, "priority_desc", null, 1);

    var first = repository.list(USER_A, firstQuery);
    var second =
        repository.list(
            USER_A, SavedPlacesQuery.of("동쪽", null, null, "priority_desc", first.nextCursor(), 1));

    assertThat(first.hasNext()).isTrue();
    assertThat(first.items()).hasSize(1);
    assertThat(second.items()).hasSize(1);
    assertThat(second.items().getFirst().placeId())
        .isNotEqualTo(first.items().getFirst().placeId());
    assertThatThrownBy(
            () ->
                repository.list(
                    USER_B,
                    SavedPlacesQuery.of("동쪽", null, null, "priority_desc", first.nextCursor(), 1)))
        .isInstanceOf(
            com.timingjeju.api.application.pagination.CursorContextMismatchException.class);
  }

  @Test
  void DELETE와_cross_owner는_동일한_not_found경계를_유지한다() {
    repository.create(
        USER_A, "saved-place-key-401", SavedPlaceCommand.create(PLACE_A, null, null, null, null));

    assertThat(repository.delete(USER_B, PLACE_A)).isFalse();
    assertThat(repository.delete(USER_A, PLACE_A)).isTrue();
    assertThat(repository.delete(USER_A, PLACE_A)).isFalse();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void 동시_same_key와_different_key_POST는_각각_single_execute와_unique_winner를_보장한다() throws Exception {
    var command = SavedPlaceCommand.create(PLACE_A, null, List.of("동쪽"), 5, 1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var sameA = future(executor, () -> repository.create(USER_A, "concurrent-same", command));
      var sameB = future(executor, () -> repository.create(USER_A, "concurrent-same", command));
      var same = List.of(sameA.get(10, TimeUnit.SECONDS), sameB.get(10, TimeUnit.SECONDS));
      assertThat(same).filteredOn(result -> result.created() && !result.replayed()).hasSize(1);
      assertThat(same).filteredOn(result -> result.created() && result.replayed()).hasSize(1);

      var otherCommand = SavedPlaceCommand.create(PLACE_B, null, List.of(), 0, null);
      var differentA =
          future(executor, () -> repository.create(USER_A, "concurrent-a", otherCommand));
      var differentB =
          future(executor, () -> repository.create(USER_A, "concurrent-b", otherCommand));
      var different =
          List.of(differentA.get(10, TimeUnit.SECONDS), differentB.get(10, TimeUnit.SECONDS));
      assertThat(different).filteredOn(result -> result.created() && !result.replayed()).hasSize(1);
      assertThat(different).filteredOn(result -> !result.created() && result.replayed()).hasSize(1);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void 동시_PATCH는_same_ETag에서_한건만_CAS_성공한다() throws Exception {
    var created =
        tx(
            () ->
                repository.create(
                    USER_A,
                    "concurrent-patch-create",
                    SavedPlaceCommand.create(PLACE_A, null, List.of(), 0, null)));
    PatchSavedPlaceRequest first = new PatchSavedPlaceRequest();
    first.setMemo("first");
    PatchSavedPlaceRequest second = new PatchSavedPlaceRequest();
    second.setMemo("second");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          futureOutcome(
              executor, () -> repository.patch(USER_A, PLACE_A, created.etag(), first.toCommand()));
      var b =
          futureOutcome(
              executor,
              () -> repository.patch(USER_A, PLACE_A, created.etag(), second.toCommand()));
      var outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertThat(outcomes).filteredOn("success", true).hasSize(1);
      assertThat(outcomes).filteredOn("code", "SAVED_PLACE_VERSION_CONFLICT").hasSize(1);
    }
  }

  @Test
  void expired_boundary와_place_hard_delete는_marker를_재사용하거나_영구_block하지_않는다() {
    repository.create(
        USER_A, "expiry-key", SavedPlaceCommand.create(PLACE_A, null, null, null, null));
    jdbc.update(
        "update public.saved_place_idempotency set expires_at=now() where owner_sub=? and idempotency_key=?",
        USER_A,
        "expiry-key");
    assertThatThrownBy(
            () ->
                repository.create(
                    USER_A,
                    "expiry-key",
                    SavedPlaceCommand.create(PLACE_A, "different", null, null, null)))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("SAVED_PLACE_ALREADY_EXISTS");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_place_idempotency where owner_sub=? and idempotency_key=?",
                Integer.class,
                USER_A,
                "expiry-key"))
        .isZero();

    jdbc.update("delete from public.tour_places where id=?", PLACE_A);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_place_idempotency where place_id=?",
                Integer.class,
                PLACE_A))
        .isZero();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void expired_cleanup은_later_command실패와_분리된_REQUIRES_NEW로_commit된다() {
    service.create(
        USER_A, "cleanup-seed", SavedPlaceCommand.create(PLACE_A, null, List.of(), 0, null));
    jdbc.update(
        "update public.saved_place_idempotency set expires_at=now() where owner_sub=? and idempotency_key=?",
        USER_A,
        "cleanup-seed");
    jdbc.update(
        """
        insert into public.saved_places_backfill_audit(
          saved_place_id,user_id,original_memo,original_tags,original_priority,
          original_target_day,reasons,captured_at,purge_after)
        select id,user_id,memo,tags,priority,target_day,array['test'],
               now()-interval '31 days',now()-interval '1 day'
        from public.saved_places where user_id=? and place_id=?
        """,
        USER_A,
        PLACE_A);

    retentionScheduler.tick();

    assertThatThrownBy(
            () ->
                service.create(
                    USER_A,
                    "cleanup-failing-command",
                    SavedPlaceCommand.create(UUID.randomUUID(), null, List.of(), 0, null)))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("PLACE_NOT_FOUND");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_place_idempotency where owner_sub=? and idempotency_key=?",
                Integer.class,
                USER_A,
                "cleanup-seed"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_places_backfill_audit where user_id=?",
                Integer.class,
                USER_A))
        .isZero();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void idempotency_snapshot은_partial_null_모든_조합을_거부한다() {
    for (int mask = 1; mask < 15; mask++) {
      int candidate = mask;
      assertThatThrownBy(
              () ->
                  insertSnapshot(
                      "partial-" + candidate,
                      (candidate & 1) == 0 ? null : 200,
                      (candidate & 2) == 0 ? null : "application/json",
                      (candidate & 4) == 0 ? null : "/api/v1/me/saved-places/" + PLACE_A,
                      (candidate & 8) == 0
                          ? null
                          : "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .isInstanceOf(DataAccessException.class);
    }
    assertThat(insertSnapshot("pending-all-null", null, null, null, null)).isOne();
    assertThat(
            insertSnapshot(
                "completed-all-present",
                201,
                "application/json",
                "/api/v1/me/saved-places/" + PLACE_A,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isOne();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void scheduler는_HTTP_traffic없이_100개_초과_marker와_audit을_bounded_batch로_drain한다() {
    int inserted =
        jdbc.update(
            """
            insert into public.saved_place_idempotency(
              owner_sub,idempotency_key,request_hash,place_id,created,response_etag,
              response_name,response_category,response_tags,response_priority,
              response_saved_at,response_updated_at,expires_at)
            select ?, 'retention-' || n, repeat('a',64), ?, false, '"etag"',
                   '장소','VE',array[]::text[],0,now(),now(),now()-interval '1 second'
            from generate_series(1,205) n
            """,
            USER_A,
            PLACE_A);
    int auditInserted =
        jdbc.update(
            """
            insert into public.saved_places_backfill_audit(
              saved_place_id,user_id,original_memo,original_tags,original_priority,
              original_target_day,reasons,captured_at,purge_after)
            select md5('audit-' || n)::uuid, ?, null, array[]::text[], 0, null,
                   array['test'], now()-interval '30 days', now()-interval '1 second'
            from generate_series(1,205) n
            """,
            USER_A);

    assertThat(inserted).isEqualTo(205);
    assertThat(auditInserted).isEqualTo(205);
    retentionScheduler.tick();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_place_idempotency where owner_sub=? and idempotency_key like 'retention-%'",
                Integer.class, USER_A))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_places_backfill_audit where user_id=?",
                Integer.class,
                USER_A))
        .isZero();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void authenticated_RLS는_owner_SELECT만_보이고_write는_default_deny다() {
    jdbc.update(
        "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['동쪽'])",
        USER_A,
        PLACE_A,
        "자기 행");
    jdbc.update(
        "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['동쪽'])",
        USER_B,
        PLACE_B,
        "다른 사용자 행");
    UUID deniedPlace = UUID.randomUUID();
    place(deniedPlace, "VE", "seongsan", null);

    withTemporaryAuthenticatedGrant(
        () -> {
          assertThat(jdbc.queryForObject("select count(*) from public.saved_places", Integer.class))
              .isEqualTo(1);
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from public.saved_places where user_id=?",
                      Integer.class,
                      USER_B))
              .isZero();
          return null;
        });
    assertThatThrownBy(
            () ->
                withTemporaryAuthenticatedGrant(
                    () ->
                        jdbc.update(
                            "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['동쪽'])",
                            USER_A,
                            deniedPlace,
                            "차단")))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            withTemporaryAuthenticatedGrant(
                () ->
                    jdbc.update(
                        "update public.saved_places set memo='차단' where user_id=? and place_id=?",
                        USER_A,
                        PLACE_A)))
        .isZero();
    assertThat(
            withTemporaryAuthenticatedGrant(
                () -> jdbc.update("delete from public.saved_places where user_id=?", USER_A)))
        .isZero();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void DB는_service_role_direct_DML의_trim_NFC_codepoint_order와_numeric경계를_거부한다() {
    assertInvalidDirectInsert("array[' 동쪽']", 0, 1, "memo");
    assertInvalidDirectInsert("array['동쪽']", 0, 1, "memo");
    assertInvalidDirectInsert("array['필수','동쪽']", 0, 1, "memo");
    assertInvalidDirectInsert("array['😀','']", 0, 1, "memo");
    assertInvalidDirectInsert("array['동쪽']", 6, 1, "memo");
    assertInvalidDirectInsert("array['동쪽']", 0, 366, "memo");
    assertInvalidDirectInsert("array['동쪽']", 0, 1, " memo ");
    assertInvalidDirectInsert("array['동쪽']", 0, 1, "동쪽");
    assertValidDirectInsert("array['\u2003동쪽\u2003']", "\u2003메모\u2003");
  }

  @Test
  void production_ACL은_authenticated_direct_CRUD를_차단하고_service_role은_최소_DML만_갖는다() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_policies where schemaname='public' and tablename='saved_places' and 'authenticated'=any(roles)",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_policies where schemaname='public' and tablename='saved_places' and cmd <> 'SELECT'",
                Integer.class))
        .isZero();
    for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('authenticated','public.saved_places',?)",
                  Boolean.class,
                  privilege))
          .as("authenticated " + privilege)
          .isFalse();
    }
    for (String table : List.of("saved_places", "saved_place_idempotency")) {
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertThat(
                jdbc.queryForObject(
                    "select has_table_privilege('service_role','public." + table + "',?)",
                    Boolean.class,
                    privilege))
            .as("service_role " + table + " " + privilege)
            .isTrue();
      }
      for (String privilege : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
        assertThat(
                jdbc.queryForObject(
                    "select has_table_privilege('service_role','public." + table + "',?)",
                    Boolean.class,
                    privilege))
            .as("service_role " + table + " " + privilege)
            .isFalse();
      }
    }
  }

  private void assertInvalidDirectInsert(String tagsSql, int priority, int targetDay, String memo) {
    UUID candidate = UUID.randomUUID();
    tx(
        () -> {
          place(candidate, "VE", "seongsan", null);
          return null;
        });
    assertThatThrownBy(
            () ->
                tx(
                    () -> {
                      jdbc.execute("set local role service_role");
                      jdbc.update(
                          "insert into public.saved_places(user_id,place_id,memo,tags,priority,target_day) values (?,?,?,"
                              + tagsSql
                              + ",?,?)",
                          USER_A,
                          candidate,
                          memo,
                          priority,
                          targetDay);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private int insertSnapshot(
      String key, Integer status, String contentType, String location, byte[] body) {
    return jdbc.update(
        """
        insert into public.saved_place_idempotency(
          owner_sub,idempotency_key,request_hash,place_id,created,response_etag,
          response_name,response_category,response_tags,response_priority,response_saved_at,
          response_updated_at,response_status,response_content_type,response_location,response_body,
          expires_at)
        values (?, ?, repeat('a',64), ?, false, '"etag"', '장소', 'VE', array[]::text[], 0,
                now(), now(), ?, ?, ?, ?, now()+interval '1 hour')
        """,
        USER_A,
        key,
        PLACE_A,
        status,
        contentType,
        location,
        body);
  }

  private void assertValidDirectInsert(String tagsSql, String memo) {
    UUID candidate = UUID.randomUUID();
    tx(
        () -> {
          place(candidate, "VE", "seongsan", null);
          jdbc.execute("set local role service_role");
          assertThat(
                  jdbc.update(
                      "insert into public.saved_places(user_id,place_id,memo,tags,priority,target_day) values (?,?,?,"
                          + tagsSql
                          + ",0,1)",
                      USER_A,
                      candidate,
                      memo))
              .isEqualTo(1);
          return null;
        });
  }

  private <T> T withTemporaryAuthenticatedGrant(java.util.function.Supplier<T> work) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              jdbc.execute(
                  "grant select,insert,update,delete on public.saved_places to authenticated");
              jdbc.execute("set local role authenticated");
              jdbc.queryForObject(
                  "select set_config('request.jwt.claim.sub',?,true)",
                  String.class,
                  USER_A.toString());
              T result = work.get();
              jdbc.execute("reset role");
              jdbc.execute("revoke all privileges on table public.saved_places from authenticated");
              return result;
            });
  }

  private <T> T tx(java.util.function.Supplier<T> work) {
    return new TransactionTemplate(transactionManager).execute(status -> work.get());
  }

  private <T> CompletableFuture<T> future(
      java.util.concurrent.Executor executor, java.util.function.Supplier<T> work) {
    return CompletableFuture.supplyAsync(() -> tx(work), executor);
  }

  private CompletableFuture<Outcome> futureOutcome(
      java.util.concurrent.Executor executor, java.util.function.Supplier<?> work) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            tx(work);
            return new Outcome(true, null);
          } catch (SavedPlaceException exception) {
            return new Outcome(false, exception.code());
          }
        },
        executor);
  }

  private record Outcome(boolean success, String code) {}

  private void user(UUID id) {
    jdbc.update("insert into auth.users(id,email) values (?,?)", id, id + "@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", id, id + "@example.test");
  }

  private void place(UUID id, String category, String region, Integer stay) {
    jdbc.update(
        """
        insert into public.tour_places(id,content_id,name,normalized_name,category,region_code,
          region_label,location,recommended_stay_minutes,source_provider,source_service)
        values (?,?,?, ?,?,?,?,ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,?,'fixture','saved-places-test')
        """,
        id,
        "content-" + id,
        "장소-" + id,
        "장소-" + id,
        category,
        region,
        region,
        stay);
  }
}
