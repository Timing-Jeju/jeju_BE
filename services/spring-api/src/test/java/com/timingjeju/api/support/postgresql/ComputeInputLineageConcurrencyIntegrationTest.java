package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.global.asyncrun.JdbcRunLeaseRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class ComputeInputLineageConcurrencyIntegrationTest {
  private static final String TARGET = "20260918000020_remove_user_location_runtime.sql";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void service_role의_정상_input_parent는_commit되고_단독_input_삭제는_롤백된다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(
          container,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("supabase/migrations")
              .resolve(TARGET));
      var source =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(source);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
      UUID run = insertCommittedInput(jdbc, transaction);
      UUID base =
          jdbc.queryForObject(
              "select schedule_version_id from public.compute_runs where id=?", UUID.class, run);
      UUID trip =
          jdbc.queryForObject(
              "select trip_plan_id from public.compute_runs where id=?", UUID.class, run);
      assertThatThrownBy(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        jdbc.execute("set local role service_role");
                        jdbc.update(
                            "delete from public.compute_run_inputs where compute_run_id=?", run);
                      }))
          .hasStackTraceContaining("permission denied");
      assertThatThrownBy(
              () ->
                  transaction.executeWithoutResult(
                      status ->
                          jdbc.update(
                              "delete from public.compute_run_inputs where compute_run_id=?", run)))
          .hasStackTraceContaining("compute input lineage required");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_run_inputs where compute_run_id=?",
                  Integer.class,
                  run))
          .isEqualTo(1);
      transaction.executeWithoutResult(
          status -> {
            jdbc.execute("set local role service_role");
            jdbc.update("delete from public.compute_runs where id=?", run);
          });
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_run_inputs where compute_run_id=?",
                  Integer.class,
                  run))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_schedule_versions where id=?",
                  Integer.class,
                  base))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_plans where id=?", Integer.class, trip))
          .isEqualTo(1);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 두_세션에서_claim과_input_삭제가_경쟁해도_commit된_작업의_input은_남는다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(
          container,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("supabase/migrations")
              .resolve(TARGET));
      var source =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(source);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
      UUID run = insertCommittedInput(jdbc, transaction);
      var parentLocked = new CountDownLatch(1);
      var inputDeleted = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var claim =
            executor.submit(
                () ->
                    transaction.execute(
                        status -> {
                          jdbc.execute("set local role service_role");
                          jdbc.execute("set local lock_timeout='10s'");
                          jdbc.queryForObject(
                              "select id from public.compute_runs where id=? for update",
                              UUID.class,
                              run);
                          parentLocked.countDown();
                          await(inputDeleted);
                          return new JdbcRunLeaseRepository(jdbc)
                              .claimAvailable("fixture-worker", Duration.ofSeconds(30), 1);
                        }));
        var deletion =
            executor.submit(
                () -> {
                  await(parentLocked);
                  transaction.executeWithoutResult(
                      status -> {
                        // The owner can DELETE; service_role remains SELECT/INSERT-only on inputs.
                        jdbc.execute("set local lock_timeout='10s'");
                        jdbc.update(
                            "delete from public.compute_run_inputs where compute_run_id=?", run);
                        inputDeleted.countDown();
                      });
                });
        assertThat(claim.get(20, TimeUnit.SECONDS)).hasSize(1);
        assertThatThrownBy(() -> deletion.get(20, TimeUnit.SECONDS))
            .hasStackTraceContaining("compute input lineage required");
      }
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_runs where id=? and status='running' and fencing_token=1",
                  Integer.class,
                  run))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_run_inputs where compute_run_id=?",
                  Integer.class,
                  run))
          .isEqualTo(1);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 생성과_수정의_부모_잠금과_입력_삭제가_경쟁해도_계보는_commit후_보존된다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(
          container,
          PostgreSqlTestContainerFactory.locateRepositoryRoot()
              .resolve("supabase/migrations")
              .resolve(TARGET));
      var source =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(source);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
      for (String kind : java.util.List.of("generation", "revision")) {
        UUID run = insertCommittedPlannerInput(jdbc, transaction, kind);
        String table =
            kind.equals("generation") ? "itinerary_generation_runs" : "schedule_revision_runs";
        String column =
            kind.equals("generation") ? "generation_run_id" : "schedule_revision_run_id";
        var parentLocked = new CountDownLatch(1);
        var inputDeleted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
          var parent =
              executor.submit(
                  () ->
                      transaction.executeWithoutResult(
                          status -> {
                            jdbc.execute("set local role service_role");
                            jdbc.execute("set local lock_timeout='10s'");
                            jdbc.queryForObject(
                                "select id from public." + table + " where id=? for update",
                                UUID.class,
                                run);
                            parentLocked.countDown();
                            await(inputDeleted);
                          }));
          var deletion =
              executor.submit(
                  () -> {
                    await(parentLocked);
                    transaction.executeWithoutResult(
                        status -> {
                          jdbc.execute("set local lock_timeout='10s'");
                          jdbc.update(
                              "delete from public.compute_run_inputs where " + column + "=?", run);
                          inputDeleted.countDown();
                        });
                  });
          parent.get(20, TimeUnit.SECONDS);
          assertThatThrownBy(() -> deletion.get(20, TimeUnit.SECONDS))
              .hasStackTraceContaining("compute input lineage required");
        }
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from public.compute_run_inputs where " + column + "=?",
                    Integer.class,
                    run))
            .isEqualTo(1);
        transaction.executeWithoutResult(
            status -> {
              jdbc.execute("set local role service_role");
              jdbc.update("delete from public." + table + " where id=?", run);
            });
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from public.compute_run_inputs where " + column + "=?",
                    Integer.class,
                    run))
            .isZero();
      }
    } finally {
      container.stop();
    }
  }

  private static UUID insertCommittedPlannerInput(
      JdbcTemplate jdbc, TransactionTemplate transaction, String kind) {
    UUID compute = insertCommittedInput(jdbc, transaction);
    UUID version =
        jdbc.queryForObject(
            "select schedule_version_id from public.compute_runs where id=?", UUID.class, compute);
    UUID trip =
        jdbc.queryForObject(
            "select trip_plan_id from public.compute_runs where id=?", UUID.class, compute);
    UUID owner =
        jdbc.queryForObject("select user_id from public.trip_plans where id=?", UUID.class, trip);
    jdbc.update("delete from public.compute_runs where id=?", compute);
    UUID day = UUID.randomUUID(), run = UUID.randomUUID();
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
        day,
        trip);
    String runType = kind.equals("generation") ? "itinerary_generation" : "schedule_revision";
    String column = kind.equals("generation") ? "generation_run_id" : "schedule_revision_run_id";
    String input =
        "{\"targetDayId\":\""
            + day
            + "\","
            + (kind.equals("generation")
                ? "\"candidateCount\":3,\"refreshExternalFacts\":false}"
                : "\"affectedItemIds\":[],\"instructionCodes\":[]}");
    String hash =
        jdbc.queryForObject(
            """
        select public.compute_command_input_hash(?::text,2::smallint,'fixture'::text,'fixture'::text,
          ?::uuid,?::jsonb)
        """,
            String.class,
            runType,
            version,
            input);
    transaction.executeWithoutResult(
        status -> {
          jdbc.execute("set local role service_role");
          if (kind.equals("generation")) {
            jdbc.update(
                """
            insert into public.itinerary_generation_runs
              (id,trip_plan_id,trip_day_id,base_schedule_version_id,structured_input,status,
               contract_version,algorithm_version,idempotency_key,requested_by_user_id)
            values (?,?,?,?,?::jsonb,'queued','fixture','fixture',?,?)
            """,
                run,
                trip,
                day,
                version,
                input,
                run.toString(),
                owner);
          } else {
            jdbc.update(
                """
            insert into public.schedule_revision_runs
              (id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,
               contract_version,algorithm_version,idempotency_key,request_hash)
            values (?,?,?,?,?,'fixture','fixture',?,?)
            """,
                run,
                owner,
                trip,
                version,
                day,
                UUID.randomUUID(),
                hash);
          }
          jdbc.update(
              "insert into public.compute_run_inputs("
                  + column
                  + ",owner_user_id,trip_plan_id,base_schedule_version_id,run_type,"
                  + "schema_version,contract_version,algorithm_version,structured_input,command_input_hash) "
                  + "values (?,?,?,?,?,2,'fixture','fixture',?::jsonb,?)",
              run,
              owner,
              trip,
              version,
              runType,
              input,
              hash);
        });
    return run;
  }

  private static UUID insertCommittedInput(JdbcTemplate jdbc, TransactionTemplate transaction) {
    UUID owner = UUID.randomUUID(),
        trip = UUID.randomUUID(),
        version = UUID.randomUUID(),
        run = UUID.randomUUID();
    String email = owner + "@lineage.test.invalid";
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, email);
    jdbc.update("insert into public.user_profiles(id,email) values (?,?)", owner, email);
    jdbc.update(
        "insert into public.trip_plans(id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version) "
            + "values (?,?,?,'lineage','draft','2026-09-01','2026-09-01','fixture','fixture')",
        trip,
        owner,
        "lineage-" + trip);
    jdbc.update(
        "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type) values (?,?,1,'draft','initial')",
        version,
        trip);
    String hash =
        jdbc.queryForObject(
            """
        select public.compute_command_input_hash('feasibility'::text,2::smallint,'fixture'::text,'fixture'::text,?::uuid,
          '{"refreshExternalFacts":false}'::jsonb)
        """,
            String.class,
            version);
    transaction.executeWithoutResult(
        status -> {
          jdbc.execute("set local role service_role");
          jdbc.update(
              "insert into public.compute_runs(id,trip_plan_id,schedule_version_id,run_type,status,input_hash,contract_version,algorithm_version) "
                  + "values (?,?,?,'feasibility','queued',?,'fixture','fixture')",
              run,
              trip,
              version,
              hash);
          jdbc.update(
              "insert into public.compute_run_inputs(compute_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,"
                  + "schema_version,contract_version,algorithm_version,structured_input,command_input_hash) "
                  + "values (?,?,?,?,'feasibility',2,'fixture','fixture','{\"refreshExternalFacts\":false}'::jsonb,?)",
              run,
              owner,
              trip,
              version,
              hash);
        });
    return run;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("fixture synchronization timeout");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fixture synchronization interrupted", failure);
    }
  }
}
