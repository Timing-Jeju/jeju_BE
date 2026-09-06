package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.domain.profile.exception.ProfileImageProblemDefinitions;
import com.timingjeju.api.global.profile.JdbcProfileImageStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class ProfileImageMigrationIntegrationTest {

  private static final String TARGET = "20260918000006_profile_image_storage.sql";
  private static final UUID PROVIDER = UUID.fromString("78000000-0000-4000-8000-000000000001");
  private static final UUID NONE = UUID.fromString("78000000-0000-4000-8000-000000000002");
  private static final UUID OVERFLOW = UUID.fromString("78000000-0000-4000-8000-000000000003");
  private static final UUID FUTURE = UUID.fromString("78000000-0000-4000-8000-000000000004");
  private static final String FIRST_KEY = NONE + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final String SECOND_KEY = NONE + "/profile/118f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static PostgreSQLContainer container;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void startAtPreviousSchemaThenApplyTarget() throws Exception {
    container = PostgreSqlTestContainerFactory.createBefore(TARGET);
    container.start();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    legacyRows(jdbc);
    PostgreSqlTestContainerFactory.executeScript(container, targetPath());
  }

  @AfterAll
  static void stop() {
    if (container != null) {
      container.stop();
    }
  }

  @BeforeEach
  void restoreDeterministicFixture() {
    jdbc.update("delete from public.profile_image_cleanup_outbox");
    jdbc.update(
        "update public.user_profiles set profile_image_url=null,profile_image_object_key=null,profile_image_source='none',profile_image_storage_etag=null,profile_image_version=0 where id in (?,?)",
        NONE,
        OVERFLOW);
  }

  @Test
  void plain_PostgreSQL_transition은_storage_DO를_skip하고_legacy_source를_lossless_backfill한다() {
    assertThat(jdbc.queryForObject("select to_regclass('storage.objects')", String.class)).isNull();
    assertThat(
            jdbc.queryForObject(
                "select profile_image_source from public.user_profiles where id=?",
                String.class,
                PROVIDER))
        .isEqualTo("provider");
    assertThat(
            jdbc.queryForObject(
                "select profile_image_url from public.user_profiles where id=?",
                String.class,
                PROVIDER))
        .isEqualTo("https://provider.example.invalid/avatar");
    assertThat(
            jdbc.queryForObject(
                "select profile_image_source from public.user_profiles where id=?",
                String.class,
                NONE))
        .isEqualTo("none");
  }

  @Test
  void target_catalog와_legacy_backfill은_exact하다() {
    assertThat(
            jdbc.queryForList(
                "select conname from pg_catalog.pg_constraint where conrelid in ('public.user_profiles'::regclass,'public.profile_image_cleanup_outbox'::regclass) and conname like 'ck_%profile_image%' order by conname",
                String.class))
        .containsExactly(
            "ck_profile_image_cleanup_object_key",
            "ck_profile_image_cleanup_owner_key",
            "ck_profile_image_cleanup_storage_etag",
            "ck_user_profiles_profile_image_object_key",
            "ck_user_profiles_profile_image_source",
            "ck_user_profiles_profile_image_state",
            "ck_user_profiles_profile_image_storage_etag",
            "ck_user_profiles_profile_image_version");
    assertThat(
            jdbc.queryForList(
                "select indexname from pg_catalog.pg_indexes where schemaname='public' and tablename='profile_image_cleanup_outbox' order by indexname",
                String.class))
        .containsExactly(
            "idx_profile_image_cleanup_claim",
            "profile_image_cleanup_outbox_object_key_storage_etag_key",
            "profile_image_cleanup_outbox_pkey");
    assertThat(
            jdbc.queryForList(
                "select id::text || ':' || profile_image_source || ':' || coalesce(profile_image_url,'<null>') from public.user_profiles where id in (?,?,?) order by id",
                String.class,
                PROVIDER,
                NONE,
                OVERFLOW))
        .containsExactly(
            PROVIDER + ":provider:https://provider.example.invalid/avatar",
            NONE + ":none:<null>",
            OVERFLOW + ":none:<null>");
  }

  @Test
  void profile_state와_outbox_column_type_nullability_default가_exact하다() {
    assertThat(
            jdbc.queryForList(
                "select column_name||':'||udt_name||':'||is_nullable||':'||coalesce(column_default,'<null>') from information_schema.columns where table_schema='public' and table_name='user_profiles' and column_name like 'profile_image_%' order by ordinal_position",
                String.class))
        .containsExactly(
            "profile_image_url:text:YES:<null>",
            "profile_image_object_key:text:YES:<null>",
            "profile_image_source:text:NO:'none'::text",
            "profile_image_storage_etag:text:YES:<null>",
            "profile_image_version:int8:NO:0");
    assertThat(
            jdbc.queryForList(
                "select column_name||':'||udt_name||':'||is_nullable||':'||coalesce(column_default,'<null>') from information_schema.columns where table_schema='public' and table_name='profile_image_cleanup_outbox' order by ordinal_position",
                String.class))
        .containsExactly(
            "id:uuid:NO:gen_random_uuid()",
            "owner_user_id:uuid:NO:<null>",
            "object_key:text:NO:<null>",
            "storage_etag:text:NO:<null>",
            "source_profile_version:int8:NO:<null>",
            "reason:text:NO:<null>",
            "status:text:NO:'pending'::text",
            "claim_token:uuid:YES:<null>",
            "claimed_at:timestamptz:YES:<null>",
            "attempt_count:int4:NO:0",
            "next_attempt_at:timestamptz:NO:now()",
            "completed_at:timestamptz:YES:<null>",
            "created_at:timestamptz:NO:now()");
    assertThat(
            jdbc.queryForList(
                "select conname from pg_catalog.pg_constraint where conrelid='public.profile_image_cleanup_outbox'::regclass and contype='c' order by conname",
                String.class))
        .containsExactly(
            "ck_cleanup_outbox_attempt_count",
            "ck_cleanup_outbox_claim_state",
            "ck_cleanup_outbox_completion_state",
            "ck_cleanup_outbox_reason",
            "ck_cleanup_outbox_source_profile_version",
            "ck_cleanup_outbox_status",
            "ck_profile_image_cleanup_object_key",
            "ck_profile_image_cleanup_owner_key",
            "ck_profile_image_cleanup_storage_etag");
  }

  @Test
  void future_profile_row는_none_version_zero_default로_시작한다() {
    jdbc.update(
        "insert into auth.users(id,email) values (?,?) on conflict (id) do nothing",
        FUTURE,
        "profile-future@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?) on conflict (id) do nothing",
        FUTURE,
        "profile-future@example.test");

    assertThat(
            jdbc.queryForMap(
                "select profile_image_source,profile_image_object_key,profile_image_storage_etag,profile_image_version from public.user_profiles where id=?",
                FUTURE))
        .containsEntry("profile_image_source", "none")
        .containsEntry("profile_image_object_key", null)
        .containsEntry("profile_image_storage_etag", null)
        .containsEntry("profile_image_version", 0L);
  }

  @Test
  void createBefore_legacy_target을_두번째_clean_DB에_replay해_fingerprint가_동일하다() throws Exception {
    PostgreSQLContainer replay = PostgreSqlTestContainerFactory.createBefore(TARGET);
    try {
      replay.start();
      JdbcTemplate replayJdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  replay.getJdbcUrl(), replay.getUsername(), replay.getPassword()));
      legacyRows(replayJdbc);
      PostgreSqlTestContainerFactory.executeScript(replay, targetPath());

      assertThat(migrationFingerprint(replayJdbc)).isEqualTo(migrationFingerprint(jdbc));
    } finally {
      replay.stop();
    }
  }

  @Test
  void actual_store는_confirm_replace_clear와_outbox를_한_transaction으로_fence한다() {
    JdbcProfileImageStore store = new JdbcProfileImageStore(jdbc);
    Instant first = Instant.parse("2026-09-03T00:00:00Z");
    ProfileImageMetadata firstMetadata = metadata(FIRST_KEY, "\"etag-1\"", first);
    transactions.executeWithoutResult(
        ignored -> store.confirm(NONE, 0, firstMetadata, () -> firstMetadata, first));

    ProfileImageMetadata secondMetadata = metadata(SECOND_KEY, "\"etag-2\"", first.plusSeconds(1));
    transactions.executeWithoutResult(
        ignored ->
            store.confirm(NONE, 1, secondMetadata, () -> secondMetadata, first.plusSeconds(1)));

    assertThat(
            jdbc.queryForObject(
                "select profile_image_version from public.user_profiles where id=?",
                Long.class,
                NONE))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.profile_image_cleanup_outbox where object_key=? and storage_etag=? and reason='replacement'",
                Integer.class,
                FIRST_KEY,
                "\"etag-1\""))
        .isOne();

    ProfileImageMetadata thirdMetadata =
        metadata(
            NONE + "/profile/218f47a1-43d2-7b6e-9fa2-11a1cc32c675",
            "\"etag-3\"",
            first.plusSeconds(2));
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored ->
                        store.confirm(
                            NONE,
                            2,
                            thirdMetadata,
                            () -> {
                              throw ProfileImageException.storageUnavailable();
                            },
                            first.plusSeconds(2))))
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo("PROFILE_IMAGE_STORAGE_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "select profile_image_object_key from public.user_profiles where id=?",
                String.class,
                NONE))
        .isEqualTo(SECOND_KEY);

    transactions.executeWithoutResult(ignored -> store.clear(NONE, 2, first.plusSeconds(3)));
    assertThat(
            jdbc.queryForMap(
                "select profile_image_source,profile_image_object_key,profile_image_storage_etag,profile_image_version from public.user_profiles where id=?",
                NONE))
        .containsEntry("profile_image_source", "none")
        .containsEntry("profile_image_version", 3L)
        .containsEntry("profile_image_object_key", null)
        .containsEntry("profile_image_storage_etag", null);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.profile_image_cleanup_outbox where object_key=? and storage_etag=? and reason='clear'",
                Integer.class,
                SECOND_KEY,
                "\"etag-2\""))
        .isOne();
  }

  @Test
  void post_write_version_overflow는_profile과_outbox를_모두_rollback한다() {
    String oldKey = OVERFLOW + "/profile/518f47a1-43d2-7b6e-9fa2-11a1cc32c675";
    String newKey = OVERFLOW + "/profile/618f47a1-43d2-7b6e-9fa2-11a1cc32c675";
    jdbc.update(
        "update public.user_profiles set profile_image_source='storage',profile_image_object_key=?,profile_image_storage_etag=?,profile_image_version=? where id=?",
        oldKey,
        "\"old-etag\"",
        Long.MAX_VALUE,
        OVERFLOW);
    JdbcProfileImageStore store = new JdbcProfileImageStore(jdbc);
    ProfileImageMetadata replacement =
        new ProfileImageMetadata(
            newKey,
            OVERFLOW,
            "image/webp",
            1,
            "\"new-etag\"",
            Instant.parse("2026-09-03T01:00:00Z"));

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored ->
                        store.confirm(
                            OVERFLOW,
                            Long.MAX_VALUE,
                            replacement,
                            () -> replacement,
                            replacement.updatedAt())))
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo("PROFILE_IMAGE_VERSION_CONFLICT");
    assertThat(new ProfileImageProblemDefinitions().definitions())
        .filteredOn(definition -> definition.code().equals("PROFILE_IMAGE_VERSION_CONFLICT"))
        .singleElement()
        .extracting(definition -> definition.status())
        .isEqualTo(409);

    assertThat(
            jdbc.queryForMap(
                "select profile_image_object_key,profile_image_storage_etag,profile_image_version from public.user_profiles where id=?",
                OVERFLOW))
        .containsEntry("profile_image_object_key", oldKey)
        .containsEntry("profile_image_storage_etag", "\"old-etag\"")
        .containsEntry("profile_image_version", Long.MAX_VALUE);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.profile_image_cleanup_outbox where owner_user_id=?",
                Integer.class,
                OVERFLOW))
        .isZero();
  }

  @Test
  void outbox_RLS와_ACL은_clients_0이고_service_role_exact_worker권한만_허용한다() {
    assertThat(
            jdbc.queryForObject(
                "select relrowsecurity from pg_catalog.pg_class where oid='public.profile_image_cleanup_outbox'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_catalog.pg_policy where polrelid='public.profile_image_cleanup_outbox'::regclass",
                Integer.class))
        .isZero();
    for (String role : new String[] {"anon", "authenticated"}) {
      for (String privilege :
          new String[] {
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"
          }) {
        assertThat(hasPrivilege(role, privilege)).as(role + " " + privilege).isFalse();
      }
    }
    for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE"}) {
      assertThat(hasPrivilege("service_role", privilege)).as(privilege).isTrue();
    }
    for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
      assertThat(hasPrivilege("service_role", privilege)).as(privilege).isFalse();
    }
    for (String role : new String[] {"anon", "authenticated", "service_role"}) {
      assertThat(
              jdbc.queryForObject(
                  "select has_function_privilege(?, 'public.sync_provider_profile_image_source()', 'EXECUTE')",
                  Boolean.class,
                  role))
          .as(role + " trigger helper EXECUTE")
          .isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "select not exists (select 1 from pg_catalog.pg_proc function_row cross join lateral aclexplode(coalesce(function_row.proacl,acldefault('f',function_row.proowner))) privilege where function_row.oid='public.sync_provider_profile_image_source()'::regprocedure and privilege.grantee=0 and lower(privilege.privilege_type)='execute')",
                Boolean.class))
        .as("PUBLIC trigger helper EXECUTE")
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select prosecdef from pg_catalog.pg_proc where oid='public.sync_provider_profile_image_source()'::regprocedure",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select proconfig::text from pg_catalog.pg_proc where oid='public.sync_provider_profile_image_source()'::regprocedure",
                String.class))
        .contains("search_path=\"\"");
  }

  @Test
  void DB_constraints는_각_column과_tuple_owner_invariant를_독립적으로_검증한다() {
    assertIntegrityViolation(
        "user key",
        () ->
            jdbc.update(
                "update public.user_profiles set profile_image_source='storage',profile_image_object_key=?,profile_image_storage_etag=? where id=?",
                "not-canonical/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                "\"valid-etag\"",
                PROVIDER));
    assertIntegrityViolation(
        "user etag",
        () ->
            jdbc.update(
                "update public.user_profiles set profile_image_source='storage',profile_image_object_key=?,profile_image_storage_etag='weak' where id=?",
                PROVIDER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                PROVIDER));
    assertIntegrityViolation(
        "user version",
        () ->
            jdbc.update(
                "update public.user_profiles set profile_image_version=-1 where id=?", PROVIDER));
    assertIntegrityViolation(
        "user source",
        () ->
            jdbc.update(
                "update public.user_profiles set profile_image_source='other' where id=?",
                PROVIDER));
    assertIntegrityViolation(
        "user tuple",
        () ->
            jdbc.update(
                "update public.user_profiles set profile_image_source='storage',profile_image_object_key=null,profile_image_storage_etag=? where id=?",
                "\"valid-etag\"",
                PROVIDER));
    assertIntegrityViolation(
        "user owner",
        () ->
            jdbc.update(
                "update public.user_profiles set profile_image_source='storage',profile_image_object_key=?,profile_image_storage_etag=? where id=?",
                FIRST_KEY,
                "\"valid-etag\"",
                PROVIDER));
    for (String source : new String[] {"provider", "none"}) {
      assertIntegrityViolation(
          source + " key",
          () ->
              jdbc.update(
                  "update public.user_profiles set profile_image_source=?,profile_image_object_key=?,profile_image_storage_etag=null where id=?",
                  source,
                  PROVIDER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                  PROVIDER));
      assertIntegrityViolation(
          source + " etag",
          () ->
              jdbc.update(
                  "update public.user_profiles set profile_image_source=?,profile_image_object_key=null,profile_image_storage_etag=? where id=?",
                  source,
                  "\"valid-etag\"",
                  PROVIDER));
      assertIntegrityViolation(
          source + " key and etag",
          () ->
              jdbc.update(
                  "update public.user_profiles set profile_image_source=?,profile_image_object_key=?,profile_image_storage_etag=? where id=?",
                  source,
                  PROVIDER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                  "\"valid-etag\"",
                  PROVIDER));
    }

    assertOutboxViolation(
        NONE,
        "bad/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
        "\"valid-etag\"",
        0,
        "outbox key");
    assertOutboxViolation(NONE, FIRST_KEY, "weak", 0, "outbox etag");
    assertOutboxViolation(NONE, FIRST_KEY, "\"valid-etag\"", -1, "outbox version");
    assertOutboxViolation(PROVIDER, FIRST_KEY, "\"valid-etag\"", 0, "outbox owner");

    assertThat(
            jdbc.update(
                "insert into public.profile_image_cleanup_outbox(owner_user_id,object_key,storage_etag,source_profile_version,reason) values (?,?,?,0,'orphan')",
                NONE,
                FIRST_KEY,
                "\"valid-etag\""))
        .isOne();
  }

  @Test
  void outbox_lifecycle_reason_counter_version_unique_invariant가_모든_state를_닫는다() {
    insertOutbox(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c601", "replacement", null, null, null, null, 0, 0);
    insertOutbox("018f47a1-43d2-7b6e-9fa2-11a1cc32c602", "orphan", "retry", null, null, null, 1, 2);
    insertOutbox(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c603",
        "clear",
        "claimed",
        "78000000-0000-4000-8000-000000000099",
        "2026-09-03T00:01:00Z",
        null,
        2,
        3);
    insertOutbox(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c604",
        "account_deletion",
        "succeeded",
        null,
        null,
        "2026-09-03T00:02:00Z",
        3,
        4);

    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c611", "invalid", null, null, null, null, 0, 0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c612", "orphan", "unknown", null, null, null, 0, 0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c613", "orphan", null, null, null, null, -1, 0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c614", "orphan", null, null, null, null, 0, -1);
    assertIntegrityViolation(
        "outbox next attempt",
        () ->
            jdbc.update(
                "insert into public.profile_image_cleanup_outbox(owner_user_id,object_key,storage_etag,source_profile_version,reason,next_attempt_at) values (?,?,?,?,?,null)",
                NONE,
                NONE + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c610",
                "\"etag-018f47a1-43d2-7b6e-9fa2-11a1cc32c610\"",
                0,
                "orphan"));
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c615",
        "orphan",
        "pending",
        "78000000-0000-4000-8000-000000000099",
        null,
        null,
        0,
        0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c616",
        "orphan",
        "claimed",
        null,
        "2026-09-03T00:01:00Z",
        null,
        0,
        0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c617",
        "orphan",
        "claimed",
        "78000000-0000-4000-8000-000000000099",
        "2026-09-03T00:01:00Z",
        "2026-09-03T00:02:00Z",
        0,
        0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c621",
        "orphan",
        "claimed",
        "78000000-0000-4000-8000-000000000099",
        null,
        null,
        0,
        0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c618",
        "orphan",
        "retry",
        "78000000-0000-4000-8000-000000000099",
        null,
        null,
        0,
        0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c622",
        "orphan",
        "retry",
        null,
        null,
        "2026-09-03T00:02:00Z",
        0,
        0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c619", "orphan", "succeeded", null, null, null, 0, 0);
    assertOutboxLifecycleViolation(
        "018f47a1-43d2-7b6e-9fa2-11a1cc32c620",
        "orphan",
        "succeeded",
        "78000000-0000-4000-8000-000000000099",
        "2026-09-03T00:01:00Z",
        "2026-09-03T00:02:00Z",
        0,
        0);

    assertIntegrityViolation(
        "outbox unique generation fence",
        () ->
            insertOutbox(
                "018f47a1-43d2-7b6e-9fa2-11a1cc32c601", "clear", null, null, null, null, 0, 0));
  }

  private static void assertOutboxLifecycleViolation(
      String generation,
      String reason,
      String status,
      String claimToken,
      String claimedAt,
      String completedAt,
      int attemptCount,
      long sourceVersion) {
    assertIntegrityViolation(
        "outbox lifecycle " + generation,
        () ->
            insertOutbox(
                generation,
                reason,
                status,
                claimToken,
                claimedAt,
                completedAt,
                attemptCount,
                sourceVersion));
  }

  private static void insertOutbox(
      String generation,
      String reason,
      String status,
      String claimToken,
      String claimedAt,
      String completedAt,
      int attemptCount,
      long sourceVersion) {
    jdbc.update(
        "insert into public.profile_image_cleanup_outbox(owner_user_id,object_key,storage_etag,source_profile_version,reason,status,claim_token,claimed_at,attempt_count,completed_at) values (?,?,?,?,?,coalesce(?,'pending'),?::uuid,?::timestamptz,?,?::timestamptz)",
        NONE,
        NONE + "/profile/" + generation,
        "\"etag-" + generation + "\"",
        sourceVersion,
        reason,
        status,
        claimToken,
        claimedAt,
        attemptCount,
        completedAt);
  }

  private static void assertOutboxViolation(
      UUID owner, String key, String etag, long version, String label) {
    assertIntegrityViolation(
        label,
        () ->
            jdbc.update(
                "insert into public.profile_image_cleanup_outbox(owner_user_id,object_key,storage_etag,source_profile_version,reason) values (?,?,?,?,'orphan')",
                owner,
                key,
                etag,
                version));
  }

  private static void assertIntegrityViolation(String label, Runnable operation) {
    assertThatThrownBy(operation::run)
        .as(label)
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private static boolean hasPrivilege(String role, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select has_table_privilege(?, 'public.profile_image_cleanup_outbox', ?)",
            Boolean.class,
            role,
            privilege));
  }

  private static ProfileImageMetadata metadata(String key, String etag, Instant updatedAt) {
    return new ProfileImageMetadata(key, NONE, "image/webp", 1024, etag, updatedAt);
  }

  private static java.util.List<Object> migrationFingerprint(JdbcTemplate source) {
    return java.util.List.of(
        source.queryForList(
            "select conname,pg_get_constraintdef(oid) definition from pg_catalog.pg_constraint where conrelid in ('public.user_profiles'::regclass,'public.profile_image_cleanup_outbox'::regclass) and conname like 'ck_%profile_image%' order by conname"),
        source.queryForList(
            "select indexname,indexdef from pg_catalog.pg_indexes where schemaname='public' and tablename='profile_image_cleanup_outbox' order by indexname"),
        source.queryForList(
            "select id::text,profile_image_url,profile_image_source,profile_image_object_key,profile_image_storage_etag,profile_image_version from public.user_profiles where id in (?,?,?) order by id",
            PROVIDER,
            NONE,
            OVERFLOW),
        source.queryForList(
            "select role_name,privilege,has_table_privilege(role_name,'public.profile_image_cleanup_outbox',privilege) allowed from (values ('anon'),('authenticated'),('service_role')) roles(role_name) cross join (values ('SELECT'),('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE'),('REFERENCES'),('TRIGGER')) privileges(privilege) order by role_name,privilege"),
        source.queryForList(
            "select rolname,has_function_privilege(rolname,'public.sync_provider_profile_image_source()','EXECUTE') allowed from pg_roles where rolname in ('anon','authenticated','service_role') order by rolname"));
  }

  private static Path targetPath() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("supabase/migrations")
        .resolve(TARGET);
  }

  private static void legacyRows(JdbcTemplate targetJdbc) {
    targetJdbc.update(
        "insert into auth.users(id,email) values (?,?), (?,?), (?,?)",
        PROVIDER,
        "profile-provider@example.test",
        NONE,
        "profile-none@example.test",
        OVERFLOW,
        "profile-overflow@example.test");
    targetJdbc.update(
        "insert into public.user_profiles(id,email,profile_image_url) values (?,?,?), (?,?,null), (?,?,null)",
        PROVIDER,
        "profile-provider@example.test",
        "https://provider.example.invalid/avatar",
        NONE,
        "profile-none@example.test",
        OVERFLOW,
        "profile-overflow@example.test");
  }
}
