package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;

class PostgreSqlRepositoryTestHarnessTest extends PostgreSqlRepositoryIntegrationTestSupport {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private Environment environment;

  private UUID rollbackUserId;

  @Test
  void Repository_통합_테스트_전용_profile로_실행된다() {
    assertThat(environment.getActiveProfiles()).containsExactly("postgresql-integration");
  }

  @Test
  void canonical_마이그레이션이_PostgreSQL16의_public_스키마에_적용된다() {
    Integer serverVersion = jdbcTemplate.queryForObject("show server_version_num", Integer.class);
    Integer tableCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from information_schema.tables
            where table_schema = 'public'
              and table_type = 'BASE TABLE'
            """,
            Integer.class);
    List<String> canonicalTables =
        jdbcTemplate.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
              and table_name in ('user_profiles', 'tour_places', 'data_import_runs')
            order by table_name
            """,
            String.class);

    assertThat(serverVersion).isBetween(160000, 169999);
    assertThat(tableCount).isGreaterThanOrEqualTo(51);
    assertThat(canonicalTables).containsExactly("data_import_runs", "tour_places", "user_profiles");
  }

  @Test
  void 필수_확장과_PostGIS_공간_쿼리를_사용할_수_있다() {
    List<String> extensions =
        jdbcTemplate.queryForList(
            """
            select extname
            from pg_extension
            where extname in ('postgis', 'pgcrypto', 'btree_gist')
            order by extname
            """,
            String.class);
    Double distanceMeters =
        jdbcTemplate.queryForObject(
            """
            select ST_Distance(
              ST_SetSRID(ST_MakePoint(126.5312, 33.4996), 4326)::geography,
              ST_SetSRID(ST_MakePoint(126.5312, 33.4996), 4326)::geography
            )
            """,
            Double.class);

    assertThat(extensions).containsExactly("btree_gist", "pgcrypto", "postgis");
    assertThat(distanceMeters).isZero();
  }

  @Test
  void canonical_마이그레이션_직후에는_fixture_행이_없다() {
    Integer fixtureRowCount =
        jdbcTemplate.queryForObject(
            """
            select
              (select count(*) from public.user_profiles)
              + (select count(*) from public.data_import_runs)
              + (select count(*) from public.tour_places)
            """,
            Integer.class);

    assertThat(fixtureRowCount).isZero();
  }

  @Test
  void 테스트에서_저장한_행은_테스트_트랜잭션과_함께_rollback된다() {
    rollbackUserId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into auth.users (id, email) values (?, ?)",
        rollbackUserId,
        rollbackUserId + "@example.test");
    jdbcTemplate.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        rollbackUserId,
        rollbackUserId + "@example.test");

    assertThat(profileCount(rollbackUserId)).isOne();
  }

  @AfterTransaction
  void 저장한_행이_실제로_rollback되었는지_확인한다() {
    if (rollbackUserId != null) {
      assertThat(profileCount(rollbackUserId)).isZero();
    }
  }

  private Integer profileCount(UUID userId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from public.user_profiles where id = ?", Integer.class, userId);
  }
}
