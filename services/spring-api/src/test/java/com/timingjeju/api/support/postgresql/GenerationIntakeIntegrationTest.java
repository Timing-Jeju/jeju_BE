package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.*;

import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.trip.TripException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.test.context.TestPropertySource(
    properties = {
      "app.schedule-generation.enabled=true",
      "app.schedule-generation.approved-airport-place-id=53000000-0000-0000-0000-000000000099"
    })
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class GenerationIntakeIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private GenerationIntakeStore intake;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private GenerationPlaceResolver placeResolver;

  @Test
  void AI_장소_변환_실패는_queued_run을_남기지_않는다() {
    var f = seed();
    org.mockito.Mockito.doThrow(GenerationException.inputUnavailable())
        .when(placeResolver)
        .resolve(org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(
            () ->
                intake.accept(
                    f.owner(),
                    f.trip(),
                    1,
                    new CreateGenerationCommand(f.day(), null, 3),
                    Instant.now()))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @Test
  void 생성된_장소_fact_ID는_승인된_canonical_장소로_역매핑하고_미지_ID는_거부한다() {
    var f = seed();
    var resolver =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationPlaceResolver(jdbc);
    String factId =
        resolver.resolve(java.util.Set.of(f.airport()), Instant.now()).factId(f.airport());
    assertThat(resolver.resolveFactIds(java.util.Set.of(factId), Instant.now()).canonicalId(factId))
        .isEqualTo(f.airport());
    assertThat(resolver.resolveFactIds(java.util.Set.of(), Instant.now()).factIds()).isEmpty();
    for (var invalid :
        java.util.List.of(
            "tourapi.place:99999999999999999999999999999999",
            "unknown:1",
            f.airport().toString(),
            "tourapi.place:1' OR true--")) {
      assertThatThrownBy(
              () -> resolver.resolveFactIds(java.util.Set.of(factId, invalid), Instant.now()))
          .hasMessage("GENERATION_INPUT_UNAVAILABLE")
          .hasNoCause();
    }
  }

  @Test
  void worker_장소_매핑은_승인된_TourAPI_계보와_전체_ID_존재를_확인한다() {
    var f = seed();
    var resolver =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationPlaceResolver(jdbc);
    var bindings = resolver.resolve(java.util.Set.of(f.airport()), Instant.now());
    String contentId =
        jdbc.queryForObject(
            "select content_id from public.tour_places where id=?", String.class, f.airport());
    assertThat(bindings.factId(f.airport())).isEqualTo("tourapi.place:" + contentId);
    assertThatThrownBy(
            () -> resolver.resolve(java.util.Set.of(f.airport(), UUID.randomUUID()), Instant.now()))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThat(resolver.resolve(java.util.Set.of(), Instant.now()).factIds()).isEmpty();
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private GenerationTripInputRepository inputs;

  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired private com.timingjeju.api.application.idempotency.IdempotencyUseCase receipts;
  private final java.util.List<Fixture> fixtures = new java.util.ArrayList<>();

  @Test
  void HTTP_완료_응답은_같은_키로_재생하고_다른_body는_409이다() throws Exception {
    var f = seed();
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    var controller =
        new com.timingjeju.api.domain.generation.controller.GenerationController(
            new com.timingjeju.api.application.generation.service.GenerationIntakeService(
                intake, java.time.Clock.systemUTC()),
            () ->
                java.util.Optional.of(
                    new com.timingjeju.api.application.security.CurrentUser(
                        f.owner(),
                        com.timingjeju.api.application.security.AuthenticatedRole.AUTHENTICATED,
                        null)),
            receipts,
            mapper);
    var path = "/api/v1/trips/" + f.trip() + "/schedule-generations";
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setContentType("application/json");
    request.addHeader("Idempotency-Key", "restart-safe,key");
    request.addHeader(
        "If-Match", com.timingjeju.api.application.trip.TripEntityTag.strong(f.trip(), 1));
    byte[] body = mapper.writeValueAsBytes(new CreateGenerationCommand(f.day(), null, 3));
    request.setContent(body);
    var first = controller.create(f.trip().toString(), request);
    assertThat(first.getStatusCode().value()).isEqualTo(202);
    request.setContent(body);
    request.removeHeader("If-Match");
    request.addHeader(
        "If-Match", com.timingjeju.api.application.trip.TripEntityTag.strong(f.trip(), 999));
    var replay = controller.create(f.trip().toString(), request);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.getHeaders().getFirst("Location"))
        .isEqualTo(first.getHeaders().getFirst("Location"));
    request.setContent(
        mapper.writeValueAsBytes(new CreateGenerationCommand(UUID.randomUUID(), null, 3)));
    assertThatThrownBy(() -> controller.create(f.trip().toString(), request))
        .isInstanceOf(com.timingjeju.api.application.idempotency.IdempotencyException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
  }

  @Test
  void snapshot_저장_실패는_run과_command도_롤백한다() {
    var f = seed();
    org.mockito.Mockito.doThrow(GenerationException.inputUnavailable())
        .when(inputs)
        .save(org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(
            () ->
                intake.accept(
                    f.owner(),
                    f.trip(),
                    1,
                    new CreateGenerationCommand(f.day(), null, 3),
                    Instant.now()))
        .isInstanceOf(GenerationException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_run_inputs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @org.junit.jupiter.api.AfterEach
  void 전용_fixture를_정리한다() {
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              for (var f : fixtures) {
                jdbc.update("delete from public.trip_plans where id=?", f.trip());
                jdbc.update("delete from auth.users where id=?", f.owner());
                jdbc.update("delete from public.tour_places where id=?", f.airport());
                jdbc.update(
                    "delete from public.external_api_snapshots where import_run_id=?",
                    f.imported());
                jdbc.update("delete from public.data_import_runs where id=?", f.imported());
              }
            });
  }

  @Test
  void 진행중인_같은_Day는_새_key라도_추가_접수하지_않는다() {
    var f = seed();
    var command = new CreateGenerationCommand(f.day(), null, 3);
    intake.accept(f.owner(), f.trip(), 1, command, Instant.now());
    assertThatThrownBy(() -> intake.accept(f.owner(), f.trip(), 1, command, Instant.now()))
        .isInstanceOf(GenerationException.class)
        .hasMessage("ACTIVE_RUN_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
  }

  @Test
  void 선박_여행은_작업을_접수하지_않는다() {
    var f = seed();
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored ->
                jdbc.update(
                    "update public.trip_transport_events set transport_type='ferry' where trip_plan_id=?",
                    f.trip()));
    assertThatThrownBy(
            () ->
                intake.accept(
                    f.owner(),
                    f.trip(),
                    1,
                    new CreateGenerationCommand(f.day(), null, 3),
                    Instant.now()))
        .isInstanceOf(GenerationException.class)
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @Test
  void 최초_접수는_저장_조건에서_snapshot과_queued만_만들고_active는_변경하지_않는다() {
    var f = seed();
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 1, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    assertThat(accepted.status()).isEqualTo("queued");
    assertThat(accepted.pollUrl())
        .isEqualTo("/api/v1/trips/" + f.trip() + "/schedule-generations/" + accepted.runId());
    assertThat(inputs.find(accepted.runId()).orElseThrow().input().places()).hasSize(1);
    assertThat(inputs.find(accepted.runId()).orElseThrow().canonicalInput())
        .doesNotContain("비공개 여행 제목");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=? and status='queued'",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id from public.trip_plans where id=?",
                UUID.class,
                f.trip()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @Test
  void 소유권과_ETag_실패는_run을_남기지_않는다() {
    var f = seed();
    var command = new CreateGenerationCommand(f.day(), null, 3);
    assertThatThrownBy(() -> intake.accept(UUID.randomUUID(), f.trip(), 1, command, Instant.now()))
        .isInstanceOf(TripException.class)
        .hasMessage("TRIP_NOT_FOUND");
    assertThatThrownBy(() -> intake.accept(f.owner(), f.trip(), 2, command, Instant.now()))
        .isInstanceOf(TripException.class)
        .hasMessage("TRIP_VERSION_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  private Fixture seed() {
    var fixture =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(ignored -> seedRows());
    fixtures.add(fixture);
    return fixture;
  }

  private Fixture seedRows() {
    UUID owner = UUID.randomUUID(), trip = UUID.randomUUID(), day = UUID.randomUUID();
    UUID airport = UUID.fromString("53000000-0000-0000-0000-000000000099");
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, owner + "@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", owner, owner + "@example.test");
    UUID imported = UUID.randomUUID(), snapshot = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.data_import_runs(id,source_kind,source_name,source_operation,data_version,status,
          parser_version,schema_version,sync_mode,scope_key,request_fingerprint,idempotency_key,source_provider,source_service)
        values (?,'tour_api','fixture','areaBasedList2','53','running','test-v1','test-v1','full','fixture-airport',?,?, 'tour-api','KorService2')
        """,
        imported,
        "a".repeat(64),
        imported.toString());
    jdbc.update(
        """
        insert into public.external_api_snapshots(id,import_run_id,source_provider,source_service,source_operation,
          scope_key,request_hash,page_key,fetched_at,parser_version,payload_hash,request_metadata_redacted,
          raw_payload,payload_size_bytes,redaction_version,payload_format,initial_parse_status,parse_status,parsed_at)
        values (?,?,'tour-api','KorService2','areaBasedList2','fixture-airport',?,'1',now(),'test-v1',?,
          '{}'::jsonb,'{}'::jsonb,2,'test-v1','JSON','parsed','parsed',now())
        """,
        snapshot,
        imported,
        "a".repeat(64),
        "f".repeat(64));
    jdbc.update(
        """
      insert into public.tour_places(id,content_id,name,normalized_name,category,region_code,location,source_provider,source_service,import_run_id,source_snapshot_id)
      values (?,'79000001','제주국제공항','제주국제공항','transport','39',ST_SetSRID(ST_MakePoint(126.49,33.50),4326),'tour-api','KorService2',?,?)
      """,
        airport,
        imported,
        snapshot);
    jdbc.update(
        "update public.data_import_runs set status='succeeded',finished_at=now() where id=?",
        imported);
    jdbc.update(
        """
      insert into public.trip_plans(id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,source_mode,data_version,revision)
      values (?,?,?,'비공개 여행 제목','draft','2026-10-01','2026-10-01','Asia/Seoul','normal','fixture','53',1)
      """,
        trip,
        owner,
        trip.toString());
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date,start_time,end_time) values (?,?,1,'2026-10-01','09:00','21:00')",
        day,
        trip);
    jdbc.update(
        "insert into public.trip_transport_modes(trip_plan_id,transport_mode,priority,is_primary) values (?,'taxi',1,true)",
        trip);
    jdbc.update(
        "insert into public.trip_place_preferences(trip_plan_id,place_id,preference_type,target_day_no,priority,requested_stay_minutes) values (?,?,'must_visit',1,100,90)",
        trip,
        airport);
    for (String type : new String[] {"arrival", "departure"}) {
      jdbc.update(
          "insert into public.trip_transport_events(trip_plan_id,event_type,transport_type,terminal_place_id,scheduled_at) values (?,?,'flight',?,?::timestamptz)",
          trip,
          type,
          airport,
          "2026-10-01T" + (type.equals("arrival") ? "10:00" : "18:00") + ":00+09:00");
    }
    return new Fixture(owner, trip, day, airport, imported);
  }

  private record Fixture(UUID owner, UUID trip, UUID day, UUID airport, UUID imported) {}
}
