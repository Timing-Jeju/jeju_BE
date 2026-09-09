package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class ProfileImageStoragePolicyMigrationIntegrationTest {

  private static final String TARGET = "20260918000006_profile_image_storage.sql";
  private static final String LAST = "20260918000017_rls_auto_enable_execute_boundary.sql";
  private static final UUID OWNER = UUID.fromString("78000000-0000-4000-8000-000000000011");
  private static final UUID OTHER = UUID.fromString("78000000-0000-4000-8000-000000000012");
  private static final String GENERATION = "018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final List<String> POSTGIS_IMAGES =
      List.of("postgis/postgis:16-3.4", "postgis/postgis:17-3.5");
  private static PostgreSQLContainer container;
  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void createStorageCompatibilityThenApplyTarget() throws Exception {
    container = PostgreSqlTestContainerFactory.createBefore(TARGET);
    container.start();
    dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    createStorageCompatibility(jdbc);
    PostgreSqlTestContainerFactory.executeScript(container, targetPath());
  }

  @AfterAll
  static void stop() {
    if (container != null) {
      container.stop();
    }
  }

  @BeforeEach
  void cleanObjects() {
    jdbc.execute("truncate storage.objects");
  }

  @Test
  void bucket과_storage_policy는_public_insert_only_contract를_exact하게_설정한다() throws Exception {
    assertThat(
            jdbc.queryForMap(
                "select name,public,file_size_limit,allowed_mime_types from storage.buckets where id='profile-images'"))
        .containsEntry("public", true)
        .containsEntry("name", "profile-images")
        .containsEntry("file_size_limit", 5_242_880L);
    assertThat(
            (String[])
                jdbc.queryForObject(
                        "select allowed_mime_types from storage.buckets where id='profile-images'",
                        java.sql.Array.class)
                    .getArray())
        .containsExactly("image/jpeg", "image/png", "image/webp");
    assertThat(
            jdbc.queryForMap(
                "select name,public,file_size_limit,allowed_mime_types from storage.buckets where id='other-bucket'"))
        .containsEntry("name", "other-bucket")
        .containsEntry("public", true)
        .containsEntry("file_size_limit", 42L);

    List<Map<String, Object>> policies =
        jdbc.queryForList(
            "select policyname,permissive,roles::text,cmd,coalesce(qual,'') qual,coalesce(with_check,'') with_check from pg_catalog.pg_policies where schemaname='storage' and tablename='objects' and policyname like 'profile_images_%' order by policyname");
    assertThat(policies)
        .extracting(
            row ->
                row.get("policyname")
                    + ":"
                    + row.get("permissive")
                    + ":"
                    + row.get("roles")
                    + ":"
                    + row.get("cmd"))
        .containsExactly(
            "profile_images_anon_insert_guard:RESTRICTIVE:{anon}:INSERT",
            "profile_images_anon_select_guard:RESTRICTIVE:{anon}:SELECT",
            "profile_images_bucket_delete_guard:RESTRICTIVE:{authenticated}:DELETE",
            "profile_images_bucket_insert_guard:RESTRICTIVE:{authenticated}:INSERT",
            "profile_images_bucket_select_guard:RESTRICTIVE:{authenticated}:SELECT",
            "profile_images_bucket_update_guard:RESTRICTIVE:{authenticated}:UPDATE",
            "profile_images_owner_insert:PERMISSIVE:{authenticated}:INSERT",
            "profile_images_owner_select:PERMISSIVE:{authenticated}:SELECT");

    Map<String, Object> ownerInsert = policy(policies, "profile_images_owner_insert");
    assertThat(ownerInsert.get("with_check").toString())
        .contains("bucket_id = 'profile-images'", "owner_id =", "auth.uid()", "::text")
        .contains("/profile/")
        .doesNotContain("metadata", "user_metadata");
    assertThat(policy(policies, "profile_images_bucket_update_guard").get("qual").toString())
        .contains("bucket_id <> 'profile-images'");
    assertThat(policy(policies, "profile_images_bucket_delete_guard").get("qual").toString())
        .contains("bucket_id <> 'profile-images'");
    assertThat(policy(policies, "profile_images_anon_insert_guard").get("with_check").toString())
        .contains("bucket_id <> 'profile-images'");
  }

  @Test
  void authenticated는_exact_owner_generation만_insert하고_profile_update_delete는_항상_거부된다()
      throws Exception {
    String key = OWNER + "/profile/" + GENERATION;
    asAuthenticated(
        OWNER,
        statement -> {
          try (var result =
              statement.executeQuery(
                  insertSqlWithoutUserMetadata("profile-images", key, OWNER, "{}")
                      + " returning id")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject(1)).isNotNull();
          }
        });
    asAuthenticated(
        OWNER,
        statement -> {
          try (var result =
              statement.executeQuery(
                  insertSqlRaw(
                          "profile-images",
                          OWNER + "/profile/118f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                          OWNER,
                          "{\"mimetype\":\"image/png\"}",
                          "{\"generation\":\"ffffffff-ffff-4fff-8fff-ffffffffffff\"}")
                      + " returning id")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject(1)).isNotNull();
          }
        });
    asAuthenticated(
        OWNER,
        statement ->
            assertThat(
                    statement.executeUpdate(
                        insertSqlRaw(
                            "profile-images",
                            OWNER + "/profile/218f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                            OWNER,
                            "{}",
                            "\"malformed-custom-metadata\"")))
                .isOne());
    asAuthenticated(
        OWNER,
        statement ->
            assertThat(
                    statement.executeUpdate(
                        insertSqlRaw(
                            "profile-images",
                            OWNER + "/profile/318f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                            OWNER,
                            "{\"mimetype\":\"image/gif\"}",
                            "{}")))
                .as("raw table RLS must not replace Storage bucket/API MIME validation")
                .isOne());

    assertDenied(
        OWNER,
        insertSql(
            "profile-images", OTHER + "/profile/" + GENERATION, OWNER, GENERATION, "image/webp"));
    assertDenied(
        OWNER, insertSql("profile-images", key + "-suffix", OWNER, GENERATION, "image/webp"));

    String uppercaseGeneration = OWNER + "/profile/018F47A1-43D2-7B6E-9FA2-11A1CC32C676";
    String malformedGeneration = OWNER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c67z";
    String alternatePath = OWNER + "/profiles/418f47a1-43d2-7b6e-9fa2-11a1cc32c675";
    for (String invalidKey :
        new String[] {uppercaseGeneration, malformedGeneration, alternatePath}) {
      assertDenied(OWNER, insertSqlWithoutUserMetadata("profile-images", invalidKey, OWNER, "{}"));
      assertNotStored(invalidKey);
    }

    assertThat(selectedCount("authenticated", OWNER, "profile-images")).isEqualTo(4);
    assertThat(selectedCount("authenticated", OTHER, "profile-images")).isZero();
    assertThat(selectedCount("anon", null, "profile-images")).isZero();
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege('anon','storage.objects','INSERT')", Boolean.class))
        .as("anon denial must come from RLS, not ACL revocation")
        .isTrue();
    String anonKey = OWNER + "/profile/418f47a1-43d2-7b6e-9fa2-11a1cc32c675";
    assertThatThrownBy(
            () ->
                asRole(
                    "anon",
                    statement ->
                        statement.executeUpdate(
                            insertSqlWithoutUserMetadata("profile-images", anonKey, OWNER, "{}"))))
        .isInstanceOf(SQLException.class)
        .extracting(failure -> ((SQLException) failure).getSQLState())
        .isEqualTo("42501");
    assertNotStored(anonKey);

    assertThat(
            affectedAsAuthenticated(
                OWNER, "update storage.objects set metadata=metadata where name='" + key + "'"))
        .isZero();
    assertThat(
            affectedAsAuthenticated(OWNER, "delete from storage.objects where name='" + key + "'"))
        .isZero();
    assertThat(
            affectedAsAuthenticated(
                OTHER, "update storage.objects set metadata=metadata where name='" + key + "'"))
        .isZero();
    assertThat(
            affectedAsAuthenticated(OTHER, "delete from storage.objects where name='" + key + "'"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from storage.objects where bucket_id='profile-images' and name=?",
                Integer.class,
                key))
        .isOne();
  }

  @Test
  void restrictive_guards는_existing_other_bucket_policy를_깨지않고_service_role_cleanup을_허용한다()
      throws Exception {
    String otherKey = "shared/unrelated.txt";
    asAuthenticated(
        OWNER,
        statement ->
            assertThat(
                    statement.executeUpdate(
                        insertSql("other-bucket", otherKey, OWNER, GENERATION, "text/plain")))
                .isOne());
    assertThat(selectedCount("authenticated", OWNER, "other-bucket")).isOne();
    assertThat(selectedCount("anon", null, "other-bucket")).isOne();
    asAuthenticated(
        OWNER,
        statement ->
            assertThat(
                    statement.executeUpdate(
                        "update storage.objects set metadata=metadata || '{\"tag\":\"kept\"}'::jsonb where bucket_id='other-bucket' and name='"
                            + otherKey
                            + "'"))
                .isOne());
    asAuthenticated(
        OWNER,
        statement ->
            assertThat(
                    statement.executeUpdate(
                        "delete from storage.objects where bucket_id='other-bucket' and name='"
                            + otherKey
                            + "'"))
                .isOne());

    String cleanupKey = OWNER + "/profile/118f47a1-43d2-7b6e-9fa2-11a1cc32c675";
    jdbc.update(
        insertSql(
            "profile-images",
            cleanupKey,
            OWNER,
            "118f47a1-43d2-7b6e-9fa2-11a1cc32c675",
            "image/png"));
    asRole(
        "service_role",
        statement ->
            statement.executeUpdate(
                "delete from storage.objects where bucket_id='profile-images' and name='"
                    + cleanupKey
                    + "'"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from storage.objects where name=?", Integer.class, cleanupKey))
        .isZero();
  }

  @Test
  void stale_bucket을_교정하고_same_DB_replay가_policy_ACL_data_fingerprint를_보존한다() throws Exception {
    List<Object> beforeReplay = storageFingerprint();
    PostgreSqlTestContainerFactory.executeScript(container, targetPath());

    assertThat(storageFingerprint()).isEqualTo(beforeReplay);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from storage.buckets where id='other-bucket' and public and file_size_limit=42",
                Integer.class))
        .isOne();
  }

  @Test
  void storage_DO는_PostgreSQL16과17에서_insertReturning_select와_불변정책을_보존한다() throws Exception {
    for (String image : POSTGIS_IMAGES) {
      int expectedMajor = image.contains(":16-") ? 16 : 17;
      PostgreSQLContainer versionContainer =
          PostgreSqlTestContainerFactory.createBefore(TARGET, image);
      try {
        versionContainer.start();
        DriverManagerDataSource versionDataSource =
            new DriverManagerDataSource(
                versionContainer.getJdbcUrl(),
                versionContainer.getUsername(),
                versionContainer.getPassword());
        JdbcTemplate versionJdbc = new JdbcTemplate(versionDataSource);
        createStorageCompatibility(versionJdbc);
        applyCanonicalThroughSecurityBoundary(versionContainer);

        assertThat(versionJdbc.queryForObject("show server_version_num", Integer.class) / 10_000)
            .as(image)
            .isEqualTo(expectedMajor);
        assertThat(
                versionJdbc.queryForObject(
                    "select public from storage.buckets where id='profile-images'", Boolean.class))
            .as(image)
            .isTrue();
        String key = OWNER + "/profile/" + GENERATION;
        asRole(
            versionDataSource,
            "authenticated",
            OWNER,
            statement -> {
              try (var result =
                  statement.executeQuery(
                      insertSqlWithoutUserMetadata("profile-images", key, OWNER, "{}")
                          + " returning id")) {
                assertThat(result.next()).as(image).isTrue();
              }
              try (var result =
                  statement.executeQuery(
                      "select count(*) from storage.objects where bucket_id='profile-images'")) {
                result.next();
                assertThat(result.getInt(1)).as(image).isOne();
              }
              assertThat(
                      statement.executeUpdate(
                          "update storage.objects set metadata=metadata where name='" + key + "'"))
                  .as(image)
                  .isZero();
              assertThat(
                      statement.executeUpdate(
                          "delete from storage.objects where name='" + key + "'"))
                  .as(image)
                  .isZero();
            });

        String wrongOwnerKey = OTHER + "/profile/" + GENERATION;
        assertThatThrownBy(
                () ->
                    asRole(
                        versionDataSource,
                        "authenticated",
                        OWNER,
                        statement ->
                            statement.executeUpdate(
                                insertSqlWithoutUserMetadata(
                                    "profile-images", wrongOwnerKey, OWNER, "{}"))))
            .as(image)
            .isInstanceOf(SQLException.class)
            .extracting(failure -> ((SQLException) failure).getSQLState())
            .isEqualTo("42501");
        String invalidKey = OWNER + "/profile/" + GENERATION + "-suffix";
        assertThatThrownBy(
                () ->
                    asRole(
                        versionDataSource,
                        "authenticated",
                        OWNER,
                        statement ->
                            statement.executeUpdate(
                                insertSqlWithoutUserMetadata(
                                    "profile-images", invalidKey, OWNER, "{}"))))
            .as(image)
            .isInstanceOf(SQLException.class)
            .extracting(failure -> ((SQLException) failure).getSQLState())
            .isEqualTo("42501");

        String anonKey = OTHER + "/profile/118f47a1-43d2-7b6e-9fa2-11a1cc32c675";
        assertThatThrownBy(
                () ->
                    asRole(
                        versionDataSource,
                        "anon",
                        null,
                        statement ->
                            statement.executeUpdate(
                                insertSqlWithoutUserMetadata(
                                    "profile-images", anonKey, OTHER, "{}"))))
            .as(image)
            .isInstanceOf(SQLException.class)
            .extracting(failure -> ((SQLException) failure).getSQLState())
            .isEqualTo("42501");
        asRole(
            versionDataSource,
            "anon",
            null,
            statement -> {
              try (var result =
                  statement.executeQuery(
                      "select count(*) from storage.objects where bucket_id='profile-images'")) {
                result.next();
                assertThat(result.getInt(1)).as(image).isZero();
              }
            });
      } finally {
        versionContainer.stop();
      }
    }
  }

  private static void assertDenied(UUID owner, String sql) {
    assertThatThrownBy(() -> asAuthenticated(owner, statement -> statement.executeUpdate(sql)))
        .isInstanceOf(SQLException.class);
  }

  private static void assertNotStored(String key) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from storage.objects where bucket_id='profile-images' and name=?",
                Integer.class,
                key))
        .isZero();
  }

  private static int affectedAsAuthenticated(UUID owner, String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("set role authenticated");
      statement.execute("select set_config('request.jwt.claim.sub','" + owner + "',false)");
      return statement.executeUpdate(sql);
    }
  }

  private static int selectedCount(String role, UUID owner, String bucket) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("set role " + role);
      if (owner != null) {
        statement.execute("select set_config('request.jwt.claim.sub','" + owner + "',false)");
      }
      try (var result =
          statement.executeQuery(
              "select count(*) from storage.objects where bucket_id='" + bucket + "'")) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static void asAuthenticated(UUID owner, SqlWork work) throws Exception {
    asRole(dataSource, "authenticated", owner, work);
  }

  private static void asRole(
      DriverManagerDataSource targetDataSource, String role, UUID owner, SqlWork work)
      throws Exception {
    try (Connection connection = targetDataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("set role " + role);
      if (owner != null) {
        statement.execute("select set_config('request.jwt.claim.sub','" + owner + "',false)");
      }
      work.run(statement);
    }
  }

  private static void asRole(String role, SqlWork work) throws Exception {
    asRole(dataSource, role, null, work);
  }

  private static String insertSql(
      String bucket, String key, UUID owner, String generation, String mime) {
    return "insert into storage.objects(bucket_id,name,owner_id,metadata,user_metadata) values ('"
        + bucket
        + "','"
        + key
        + "','"
        + owner
        + "','{\"mimetype\":\""
        + mime
        + "\"}'::jsonb,'{\"generation\":\""
        + generation
        + "\"}'::jsonb)";
  }

  private static String insertSqlWithoutUserMetadata(
      String bucket, String key, UUID owner, String metadataJson) {
    return "insert into storage.objects(bucket_id,name,owner_id,metadata) values ('"
        + bucket
        + "','"
        + key
        + "','"
        + owner
        + "','"
        + metadataJson
        + "'::jsonb)";
  }

  private static String insertSqlRaw(
      String bucket, String key, UUID owner, String metadataJson, String userMetadataJson) {
    return "insert into storage.objects(bucket_id,name,owner_id,metadata,user_metadata) values ('"
        + bucket
        + "','"
        + key
        + "','"
        + owner
        + "','"
        + metadataJson
        + "'::jsonb,'"
        + userMetadataJson
        + "'::jsonb)";
  }

  private static Map<String, Object> policy(List<Map<String, Object>> policies, String name) {
    return policies.stream()
        .filter(row -> name.equals(row.get("policyname")))
        .findFirst()
        .orElseThrow();
  }

  private static List<Object> storageFingerprint() {
    return List.of(
        jdbc.queryForList(
            "select id,name,public,file_size_limit,allowed_mime_types::text from storage.buckets order by id"),
        jdbc.queryForList(
            "select policyname,permissive,roles::text,cmd,coalesce(qual,'') qual,coalesce(with_check,'') with_check from pg_catalog.pg_policies where schemaname='storage' and tablename='objects' order by policyname"),
        jdbc.queryForList(
            "select role_name,privilege,has_table_privilege(role_name,'storage.objects',privilege) allowed from (values ('anon'),('authenticated'),('service_role')) roles(role_name) cross join (values ('SELECT'),('INSERT'),('UPDATE'),('DELETE')) privileges(privilege) order by role_name,privilege"));
  }

  private static Path targetPath() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("supabase/migrations")
        .resolve(TARGET);
  }

  private static void applyCanonicalThroughSecurityBoundary(PostgreSQLContainer targetContainer)
      throws Exception {
    boolean apply = false;
    for (Path migration :
        PostgreSqlTestContainerFactory.canonicalInitScripts(
            PostgreSqlTestContainerFactory.locateRepositoryRoot())) {
      String name = migration.getFileName().toString();
      if (TARGET.equals(name)) {
        apply = true;
      }
      if (!apply) {
        continue;
      }
      PostgreSqlTestContainerFactory.executeScript(targetContainer, migration);
      if (LAST.equals(name)) {
        return;
      }
    }
    throw new IllegalStateException("profile image canonical replay boundary가 없습니다: " + LAST);
  }

  private static void createStorageCompatibility(JdbcTemplate targetJdbc) {
    targetJdbc.execute("create schema storage");
    targetJdbc.execute(
        "create table storage.buckets(id text primary key,name text not null,public boolean not null default false,file_size_limit bigint,allowed_mime_types text[])");
    targetJdbc.execute(
        "create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text not null,name text not null,owner_id text,metadata jsonb default '{}'::jsonb,user_metadata jsonb default '{}'::jsonb,unique(bucket_id,name))");
    targetJdbc.execute("alter table storage.objects enable row level security");
    targetJdbc.execute("grant usage on schema storage,auth to authenticated,anon,service_role");
    targetJdbc.execute("grant execute on function auth.uid() to authenticated,anon");
    targetJdbc.execute("grant select,insert,update,delete on storage.objects to authenticated");
    targetJdbc.execute("grant select,insert on storage.objects to anon");
    targetJdbc.execute("grant all on storage.objects to service_role");
    targetJdbc.execute(
        "create policy existing_permissive_insert on storage.objects for insert to authenticated with check (true)");
    targetJdbc.execute(
        "create policy existing_permissive_update on storage.objects for update to authenticated using (true) with check (true)");
    targetJdbc.execute(
        "create policy existing_permissive_delete on storage.objects for delete to authenticated using (true)");
    targetJdbc.execute(
        "create policy existing_permissive_select on storage.objects for select to authenticated using (true)");
    targetJdbc.execute(
        "create policy existing_permissive_anon_select on storage.objects for select to anon using (true)");
    targetJdbc.execute(
        "create policy existing_permissive_anon_insert on storage.objects for insert to anon with check (true)");
    targetJdbc.execute(
        "insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values ('profile-images','stale-profile',false,1,array['text/plain']),('other-bucket','other-bucket',true,42,array['text/plain'])");
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Statement statement) throws Exception;
  }
}
