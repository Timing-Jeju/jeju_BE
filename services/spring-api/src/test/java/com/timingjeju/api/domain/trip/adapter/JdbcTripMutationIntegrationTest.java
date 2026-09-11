package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputRequest;
import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.trip.PatchTripCommand;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.application.trip.TripMutationResult;
import com.timingjeju.api.application.trip.TripPatchValue;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.TripUpdateRecord;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcTripMutationIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("45000000-0000-0000-0000-000000000101");
  private static final UUID OTHER = UUID.fromString("45000000-0000-0000-0000-000000000102");
  private static final UUID TRIP = UUID.fromString("45000000-0000-0000-0000-000000000103");
  private static final UUID VERSION = UUID.fromString("45000000-0000-0000-0000-000000000104");
  private static final UUID IMPORT_RUN = UUID.fromString("45000000-0000-0000-0000-000000000105");
  private static final UUID PLACE = UUID.fromString("45000000-0000-0000-0000-000000000106");
  private static final UUID REVISION_RUN = UUID.fromString("45000000-0000-0000-0000-000000000107");
  private static final Instant NOW = Instant.parse("2026-09-01T04:00:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcTripStore store;
  @Autowired private com.timingjeju.api.application.trip.service.TripService tripService;
  @Autowired private com.timingjeju.api.application.idempotency.IdempotencyUseCase idempotency;

  @Autowired
  private com.timingjeju.api.domain.trip.controller.TripProblemExceptionHandler tripProblemHandler;

  @Autowired private JdbcTripDayActivityWindowStore activityWindows;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private com.timingjeju.api.global.idempotency.JdbcIdempotencyRecordRepository receipts;
  @Autowired private CommandInputCanonicalizer commandInputCanonicalizer;
  @Autowired private CommandInputSnapshotRepository commandInputRepository;

  @BeforeEach
  void setUp() {
    clean();
    insertOwner(OWNER, "trip-mutation-owner@issue45.test");
    insertOwner(OTHER, "trip-mutation-other@issue45.test");
    insertTrip("draft");
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from public.api_idempotency_records where owner_sub = ?", OWNER);
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.tour_places where id = ?", PLACE);
    jdbc.update("delete from public.data_import_runs where id = ?", IMPORT_RUN);
    jdbc.update("delete from public.user_profiles where id in (?, ?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?, ?)", OWNER, OTHER);
  }

  @Test
  void GET_도중_활동창이_바뀌어도_revision과_Day는_같은_스냅샷이다() throws Exception {
    var rootRead = new CountDownLatch(1);
    var continueRead = new CountDownLatch(1);
    var delayed =
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc) {
          @Override
          public <T> List<T> query(
              String sql,
              java.util.Map<String, ?> params,
              org.springframework.jdbc.core.RowMapper<T> mapper) {
            var result = super.query(sql, params, mapper);
            if (sql.contains("from public.trip_plans p")) {
              rootRead.countDown();
              try {
                if (!continueRead.await(15, java.util.concurrent.TimeUnit.SECONDS))
                  throw new AssertionError("동시 writer 완료 대기 초과");
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
              }
            }
            return result;
          }
        };
    var proxy =
        new org.springframework.aop.framework.ProxyFactory(new JdbcTripStore(jdbc, delayed));
    proxy.addAdvice(
        new org.springframework.transaction.interceptor.TransactionInterceptor(
            transactions,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    var reader = (com.timingjeju.api.application.trip.TripStore) proxy.getProxy();
    var command = activityCommand();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reading = executor.submit(() -> reader.findOwned(OWNER, TRIP, NOW).orElseThrow());
      try {
        assertThat(rootRead.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        activityWindows.replace(OWNER, TRIP, 1, command, NOW.plusSeconds(1));
      } finally {
        continueRead.countDown();
      }
      var snapshot = reading.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(snapshot.revision()).isEqualTo(1);
      assertThat(snapshot.days()).allSatisfy(day -> assertThat(day.activityStartTime()).isNull());
    }
    assertThat(store.findOwned(OWNER, TRIP, NOW).orElseThrow().revision()).isEqualTo(2);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 5, 30})
  void 하루_닷새와_기존_30일여행의_활동시간은_재조회로_복원된다(int count) {
    var start = LocalDate.of(2026, 9, 1);
    store.updateOwned(record(dates(start, start.plusDays(count - 1)), 1, NOW));
    var expected = activityCommand();
    activityWindows.replace(OWNER, TRIP, 2, expected, NOW.plusSeconds(1));
    var actual = store.findOwned(OWNER, TRIP, NOW.plusSeconds(2)).orElseThrow();
    assertThat(actual.days()).hasSize(count);
    assertThat(actual.revision()).isEqualTo(3);
    for (int index = 0; index < count; index++) {
      assertThat(actual.days().get(index).activityStartTime())
          .isEqualTo(expected.days().get(index).startTime());
      assertThat(actual.days().get(index).activityEndTime())
          .isEqualTo(expected.days().get(index).endTime());
    }
  }

  @Test
  void 배포전_POST_receipt는_24시간_동안_원본을_재전송하고_여행을_중복생성하지_않는다() throws Exception {
    byte[] original =
        java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("../../fixtures/contracts/trips/legacy-create-replay.json"));
    byte[] body =
        "{\"title\":\"과거 생성\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-03\"}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = "23900000-0000-0000-0000-000000000099";
    var request =
        com.timingjeju.api.application.idempotency.IdempotencyRequest.create(
            OWNER, "POST", "/api/v1/trips", key, body);
    Instant completed =
        Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var acquired =
        receipts.acquire(request.scope(), request.requestHash(), completed.minusSeconds(1));
    String location = "/api/v1/trips/" + objectMapper.readTree(original).path("tripId").asText();
    String etag = "\"trip-legacy-v1\"";
    var originalResponse =
        new com.timingjeju.api.application.idempotency.IdempotencyResponse(
            201,
            List.of(
                new com.timingjeju.api.application.idempotency.IdempotencyHeader(
                    "Content-Type", "application/json"),
                new com.timingjeju.api.application.idempotency.IdempotencyHeader(
                    "Location", location),
                new com.timingjeju.api.application.idempotency.IdempotencyHeader("ETag", etag)),
            original);
    receipts.complete(
        request.scope(),
        request.requestHash(),
        acquired.attemptToken().orElseThrow(),
        originalResponse,
        completed);
    long before =
        jdbc.queryForObject(
            "select count(*) from public.trip_plans where user_id = ?", Long.class, OWNER);
    var replay =
        activityMvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/trips")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andReturn()
            .getResponse();
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.getHeader("ETag")).isEqualTo(etag);
    assertThat(replay.getHeader("Location")).isEqualTo(location);
    assertThat(replay.getContentAsByteArray()).isEqualTo(original);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_plans where user_id = ?", Long.class, OWNER))
        .isEqualTo(before);
    Instant expires = completed.plus(java.time.Duration.ofHours(24));
    assertThat(
            receipts
                .acquire(request.scope(), request.requestHash(), expires.minusNanos(1000))
                .response()
                .orElseThrow()
                .body())
        .isEqualTo(original);
    assertThat(receipts.acquire(request.scope(), request.requestHash(), expires).disposition())
        .isEqualTo(
            com.timingjeju.api.application.idempotency.IdempotencyAcquisition.Disposition.ACQUIRED);
  }

  @Test
  void HTTP_저장_새컨트롤러_재조회와_멱등_replay는_DB스냅샷을_보존한다() throws Exception {
    String path = "/api/v1/trips/" + TRIP + "/day-activity-windows";
    String etag = com.timingjeju.api.application.trip.TripEntityTag.strong(TRIP, 1);
    byte[] body =
        objectMapper.writeValueAsBytes(
            java.util.Map.of(
                "days",
                activityCommand().days().stream()
                    .map(
                        day ->
                            java.util.Map.of(
                                "dayId",
                                day.dayId().toString(),
                                "startTime",
                                day.startTime().toString(),
                                "endTime",
                                day.endTime().toString()))
                    .toList()));
    var first =
        activityMvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                    .header("If-Match", etag)
                    .header("Idempotency-Key", "activity-239-http")
                    .contentType("application/json")
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Idempotency-Replayed", "false"))
            .andReturn()
            .getResponse();
    var replay =
        activityMvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                    .header("If-Match", etag)
                    .header("Idempotency-Key", "activity-239-http")
                    .contentType("application/json")
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Idempotency-Replayed", "true"))
            .andReturn()
            .getResponse();
    assertThat(replay.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
    assertThat(replay.getHeader("ETag")).isEqualTo(first.getHeader("ETag"));
    activityMvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/trips/" + TRIP))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.days[0].activityStartTime")
                .value("10:00"));
    activityMvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .header("If-Match", etag)
                .header("Idempotency-Key", "activity-239-http")
                .contentType("application/json")
                .content(
                    new String(body, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("18:00", "19:00")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(root("revision", Long.class)).isEqualTo(2L);
  }

  private org.springframework.test.web.servlet.MockMvc activityMvc() {
    var users =
        org.mockito.Mockito.mock(com.timingjeju.api.application.security.CurrentUserAccessor.class);
    org.mockito.Mockito.when(users.getRequired())
        .thenReturn(
            new com.timingjeju.api.application.security.CurrentUser(
                OWNER,
                com.timingjeju.api.application.security.AuthenticatedRole.AUTHENTICATED,
                null));
    var controller =
        new com.timingjeju.api.domain.trip.controller.TripController(
            tripService,
            users,
            idempotency,
            objectMapper,
            new com.timingjeju.api.application.trip.service.TripDayActivityWindowService(
                activityWindows, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)));
    return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(tripProblemHandler)
        .build();
  }

  @Test
  void 활성일정이_참조하는_Day의_활동창변경은_재생성충돌로_거부한다() {
    installActiveSchedule();
    String before = fingerprint();
    assertCode(
        () -> activityWindows.replace(OWNER, TRIP, 1, activityCommand(), NOW),
        "TRIP_REGENERATION_REQUIRED");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void 두세션의_활동창변경은_같은_ETag에서_한번만_성공한다() throws Exception {
    var command = activityCommand();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<String> operation =
          () -> {
            ready.countDown();
            start.await();
            try {
              activityWindows.replace(OWNER, TRIP, 1, command, NOW);
              return "success";
            } catch (TripException failure) {
              return failure.code();
            }
          };
      var first = executor.submit(operation);
      var second = executor.submit(operation);
      assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("success", "TRIP_VERSION_CONFLICT");
    }
    assertThat(root("revision", Long.class)).isEqualTo(2L);
  }

  @Test
  void 두번째_Day_DB실패는_첫_Day와_revision도_롤백한다() {
    var command = activityCommand();
    String before = fingerprint();
    jdbc.execute(
        "create function public.issue239_fail_second() returns trigger language plpgsql as $$ begin if new.day_no=2 then raise exception 'fixture failure'; end if; return new; end; $$");
    jdbc.execute(
        "create trigger issue239_fail_second before update of start_time on public.trip_days for each row execute function public.issue239_fail_second()");
    try {
      assertCode(
          () -> activityWindows.replace(OWNER, TRIP, 1, command, NOW), "TRIP_DATA_UNAVAILABLE");
      assertThat(fingerprint()).isEqualTo(before);
      assertThat(store.findOwned(OWNER, TRIP, NOW).orElseThrow().days())
          .allSatisfy(day -> assertThat(day.activityStartTime()).isNull());
    } finally {
      jdbc.execute("drop trigger issue239_fail_second on public.trip_days");
      jdbc.execute("drop function public.issue239_fail_second()");
    }
  }

  @Test
  void 활동시간_전체교체는_revision을_한번_증가시키고_새조회와_noop을_보존한다() {
    var command = activityCommand();
    var saved = activityWindows.replace(OWNER, TRIP, 1, command, NOW);
    assertThat(saved.revision()).isEqualTo(2);
    assertThat(saved.days())
        .allSatisfy(
            day -> {
              assertThat(day.activityStartTime())
                  .isEqualTo(java.time.LocalTime.of(9 + day.dayNo() % 9, 0));
              assertThat(day.activityEndTime()).isEqualTo(java.time.LocalTime.of(18, 0));
            });
    assertThat(store.findOwned(OWNER, TRIP, NOW).orElseThrow().days()).isEqualTo(saved.days());
    assertThat(activityWindows.replace(OWNER, TRIP, 2, command, NOW.plusSeconds(1)).revision())
        .isEqualTo(2);
  }

  @Test
  void 누락Day_외부Day_비소유자_stale은_어느활동시간도_변경하지않는다() {
    var command = activityCommand();
    String before = fingerprint();
    assertCode(() -> activityWindows.replace(OTHER, TRIP, 1, command, NOW), "TRIP_NOT_FOUND");
    assertCode(
        () -> activityWindows.replace(OWNER, TRIP, 2, command, NOW), "TRIP_VERSION_CONFLICT");
    var missing =
        new com.timingjeju.api.application.trip.ReplaceTripDayActivityWindowsCommand(
            command.days().subList(0, 2));
    assertCode(
        () -> activityWindows.replace(OWNER, TRIP, 1, missing, NOW), "TRIP_CONSTRAINT_VIOLATION");
    var foreign = new ArrayList<>(command.days());
    foreign.set(
        2,
        new com.timingjeju.api.application.trip.TripDayActivityWindow(
            UUID.randomUUID(), java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0)));
    assertCode(
        () ->
            activityWindows.replace(
                OWNER,
                TRIP,
                1,
                new com.timingjeju.api.application.trip.ReplaceTripDayActivityWindowsCommand(
                    foreign),
                NOW),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(fingerprint()).isEqualTo(before);
    assertThat(store.findOwned(OWNER, TRIP, NOW).orElseThrow().days())
        .allSatisfy(day -> assertThat(day.activityStartTime()).isNull());
  }

  private com.timingjeju.api.application.trip.ReplaceTripDayActivityWindowsCommand
      activityCommand() {
    return new com.timingjeju.api.application.trip.ReplaceTripDayActivityWindowsCommand(
        store.findOwned(OWNER, TRIP, NOW).orElseThrow().days().stream()
            .map(
                day ->
                    new com.timingjeju.api.application.trip.TripDayActivityWindow(
                        day.dayId(),
                        java.time.LocalTime.of(9 + day.dayNo() % 9, 0),
                        java.time.LocalTime.of(18, 0)))
            .toList());
  }

  @Test
  void 새조회는_저장된_활동시간과_미입력_null을_복원한다() {
    jdbc.update(
        "update public.trip_days set start_time='10:15', end_time='17:45' where trip_plan_id=? and day_no=2",
        TRIP);
    var days = store.findOwned(OWNER, TRIP, NOW).orElseThrow().days();
    assertThat(days.get(1))
        .extracting("activityStartTime", "activityEndTime")
        .containsExactly(java.time.LocalTime.of(10, 15), java.time.LocalTime.of(17, 45));
    assertThat(days.getFirst())
        .extracting("activityStartTime", "activityEndTime")
        .containsExactly(null, null);
  }

  @Test
  void 날짜변경은_겹치는_Day_ID와_사용자_활동시간을_보존한다() {
    UUID retained =
        jdbc.queryForObject(
            "select id from public.trip_days where trip_plan_id=? and trip_date='2026-09-02'",
            UUID.class,
            TRIP);
    jdbc.update(
        "update public.trip_days set start_time='10:15', end_time='17:45' where id=?", retained);
    var changed =
        store.updateOwned(
            record(dates(LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-04")), 1, NOW));
    assertThat(changed.trip().days())
        .filteredOn(day -> day.date().equals(LocalDate.parse("2026-09-02")))
        .extracting(day -> day.dayId())
        .containsExactly(retained);
    assertThat(
            jdbc.queryForObject(
                "select start_time::text || '/' || end_time::text from public.trip_days where id=?",
                String.class,
                retained))
        .isEqualTo("10:15:00/17:45:00");
    var shrunk =
        store.updateOwned(
            record(
                dates(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-04")),
                2,
                NOW.plusSeconds(1)));
    assertThat(shrunk.trip().days().getFirst().dayId()).isEqualTo(retained);
    assertThat(shrunk.trip().days().getFirst().dayNo()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_days where trip_plan_id=? and trip_date='2026-09-04' and start_time is null and end_time is null",
                Integer.class,
                TRIP))
        .isEqualTo(1);
  }

  @Test
  void title_only는_revision만_한번_증가시키고_active와_status를_유지한다() {
    TripMutationResult result = store.updateOwned(record(title("제주 가족 여행"), 1, NOW));

    assertThat(result.scheduleEffect()).isEqualTo("maintained");
    assertThat(result.regenerationRequired()).isFalse();
    assertThat(result.trip().revision()).isEqualTo(2);
    assertThat(result.trip().title()).isEqualTo("제주 가족 여행");
    assertThat(result.trip().status()).isEqualTo("draft");
    assertThat(result.trip().activeScheduleVersionId()).isNull();
    assertThat(root("revision", Long.class)).isEqualTo(2L);
  }

  @Test
  void 일정없는_날짜변경은_달력을_정확히_재구성하고_교통이나숙소_범위밖이면_원자실패한다() {
    TripMutationResult changed =
        store.updateOwned(
            record(dates(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-05")), 1, NOW));

    assertThat(changed.scheduleEffect()).isEqualTo("none");
    assertThat(changed.trip().days())
        .extracting(day -> day.date().toString())
        .containsExactly("2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05");
    assertThat(changed.trip().revision()).isEqualTo(2);

    jdbc.update(
        "insert into public.trip_transport_events (trip_plan_id, event_type, transport_type,"
            + " terminal_name, scheduled_at) values (?, 'arrival', 'flight', '제주공항',"
            + " '2026-09-02T00:00:00Z')",
        TRIP);
    String before = fingerprint();
    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-05")),
                    2,
                    NOW.plusSeconds(1))),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void 도착출발_event가_있으면_여행경계_확장도_exact_date_invariant로_원자거부한다() {
    jdbc.update(
        "insert into public.trip_transport_events (trip_plan_id, event_type, transport_type,"
            + " terminal_name, scheduled_at) values (?, 'arrival', 'flight', '제주공항',"
            + " '2026-09-01T00:00:00Z')",
        TRIP);
    jdbc.update(
        "insert into public.trip_transport_events (trip_plan_id, event_type, transport_type,"
            + " terminal_name, scheduled_at) values (?, 'departure', 'flight', '제주공항',"
            + " '2026-09-03T00:00:00Z')",
        TRIP);
    String before = calendarFingerprint();

    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-03")), 1, NOW)),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(calendarFingerprint()).isEqualTo(before);

    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 1, NOW)),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(calendarFingerprint()).isEqualTo(before);
  }

  @Test
  void Day3_장소선호가_있는_여행을_2일로_축소하면_도메인422와_aggregate무변경을_보장한다() {
    installExternalFactReference();
    jdbc.update(
        "insert into public.trip_place_preferences (trip_plan_id, place_id, preference_type,"
            + " target_day_no, priority) values (?, ?, 'must_visit', 3, 90)",
        TRIP,
        PLACE);
    String before = calendarFingerprint();

    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02")), 1, NOW)),
        "TRIP_CONSTRAINT_VIOLATION");

    assertThat(calendarFingerprint()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select target_day_no from public.trip_place_preferences where trip_plan_id=? and"
                    + " place_id=?",
                Integer.class,
                TRIP,
                PLACE))
        .isEqualTo(3);
  }

  @Test
  void DB직접_root축소도_named_23514로_거부하고_preference와_revision을_보존한다() {
    installExternalFactReference();
    jdbc.update(
        "insert into public.trip_place_preferences (trip_plan_id, place_id, preference_type,"
            + " target_day_no, priority) values (?, ?, 'must_visit', 3, 90)",
        TRIP,
        PLACE);
    String before = calendarFingerprint();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.trip_plans set end_date='2026-09-02', revision=revision+1 where"
                        + " id=?",
                    TRIP))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));

    assertThat(calendarFingerprint()).isEqualTo(before);
  }

  @Test
  void pace변경은_active를_superseded하고_pointer_score_status를_원자무효화한다() {
    installActiveSchedule();

    TripMutationResult result = store.updateOwned(record(pace("slow"), 1, NOW));

    assertThat(result.scheduleEffect()).isEqualTo("invalidated");
    assertThat(result.regenerationRequired()).isTrue();
    assertThat(result.trip().status()).isEqualTo("draft");
    assertThat(result.trip().activeScheduleVersionId()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id = ?",
                String.class,
                VERSION))
        .isEqualTo("superseded");
  }

  @Test
  void active가_없어도_교통선호_실제변경은_draft와_재생성필요를_명시하고_전체교체한다() {
    List<TripTransportMode> desired =
        List.of(
            new TripTransportMode("taxi", 1, true),
            new TripTransportMode("public_transit", 2, false));

    TripMutationResult result = store.updateOwned(record(modes(desired), 1, NOW));

    assertThat(result.scheduleEffect()).isEqualTo("invalidated");
    assertThat(result.regenerationRequired()).isTrue();
    assertThat(result.trip().status()).isEqualTo("draft");
    assertThat(result.trip().transportModes()).containsExactlyElementsOf(desired);
    assertThat(result.trip().revision()).isEqualTo(2);
  }

  @Test
  void 일정없는_날짜변경은_1일과_30일을_허용하고_31일을_원자거부한다() {
    TripMutationResult oneDay =
        store.updateOwned(
            record(dates(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-02")), 1, NOW));
    assertThat(oneDay.trip().days()).hasSize(1);

    TripMutationResult thirtyDays =
        store.updateOwned(
            record(
                dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")),
                2,
                NOW.plusSeconds(1)));
    assertThat(thirtyDays.trip().days()).hasSize(30);
    String before = fingerprint();

    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-01")),
                    3,
                    NOW.plusSeconds(2))),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void 일정버전_날짜변경_stale_cross_owner_terminal은_각_code와_무변경을_보장한다() {
    installDraftSchedule();
    String initial = fingerprint();
    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02")), 1, NOW)),
        "TRIP_REGENERATION_REQUIRED");
    assertThat(fingerprint()).isEqualTo(initial);

    assertCode(() -> store.updateOwned(record(title("stale"), 2, NOW)), "TRIP_VERSION_CONFLICT");
    TripUpdateRecord mismatchedTag =
        new TripUpdateRecord(
            OWNER, TRIP, new TripExpectedRevision(OTHER, 1), title("wrong-tag"), ids(), NOW);
    assertCode(() -> store.updateOwned(mismatchedTag), "TRIP_VERSION_CONFLICT");
    TripUpdateRecord other =
        new TripUpdateRecord(
            OTHER, TRIP, new TripExpectedRevision(TRIP, 1), title("other"), ids(), NOW);
    assertCode(() -> store.updateOwned(other), "TRIP_NOT_FOUND");

    jdbc.update("update public.trip_plans set status = 'failed' where id = ?", TRIP);
    assertCode(
        () -> store.updateOwned(record(title("terminal"), 1, NOW)), "TRIP_TERMINAL_STATE_CONFLICT");
  }

  @Test
  void 같은_revision의_동시_writer는_정확히_하나만_성공한다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> updateAfter(start, "동시 수정 A"));
      var second = pool.submit(() -> updateAfter(start, "동시 수정 B"));
      start.countDown();

      List<String> outcomes = List.of(first.get(), second.get());
      assertThat(outcomes).containsExactlyInAnyOrder("success", "TRIP_VERSION_CONFLICT");
      assertThat(root("revision", Long.class)).isEqualTo(2L);
    }
  }

  @Test
  void departure_event와_root_endDate를_동시에_확장해도_둘다_거부되고_revision과_event는_불변이다() throws Exception {
    jdbc.update(
        "insert into public.trip_transport_events (trip_plan_id, event_type, transport_type,"
            + " terminal_name, scheduled_at) values (?, 'departure', 'flight', '제주공항',"
            + " '2026-09-03T00:00:00Z')",
        TRIP);
    String before = calendarFingerprint();
    CountDownLatch start = new CountDownLatch(1);

    try (var pool = Executors.newFixedThreadPool(2)) {
      var rootUpdate =
          pool.submit(
              () -> {
                start.await();
                try {
                  store.updateOwned(
                      record(
                          dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")),
                          1,
                          NOW));
                  return "success";
                } catch (TripException failure) {
                  return failure.code();
                }
              });
      var eventUpdate =
          pool.submit(
              () -> {
                start.await();
                try {
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          ignored ->
                              jdbc.update(
                                  "update public.trip_transport_events set"
                                      + " scheduled_at='2026-09-04T00:00:00Z' where trip_plan_id=?"
                                      + " and event_type='departure'",
                                  TRIP));
                  return "success";
                } catch (org.springframework.dao.DataAccessException failure) {
                  return "TRIP_CONSTRAINT_VIOLATION";
                }
              });
      start.countDown();

      assertThat(List.of(rootUpdate.get(), eventUpdate.get()))
          .containsOnly("TRIP_CONSTRAINT_VIOLATION");
    }
    assertThat(calendarFingerprint()).isEqualTo(before);
  }

  @Test
  void PATCH와_DELETE_경합은_직렬화되고_삭제뒤_aggregate가_남지않는다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var update = pool.submit(() -> updateAfter(start, "삭제와 경합하는 수정"));
      var delete = pool.submit(() -> deleteAfter(start));
      start.countDown();

      assertThat(delete.get()).isEqualTo("success");
      assertThat(update.get()).isIn("success", "TRIP_NOT_FOUND");
      assertThat(count("trip_plans", "id", TRIP)).isZero();
    }
  }

  @Test
  void delete는_aggregate만_cascade하고_외부fact와_user를_보존하며_repeat_owner_live_run을_차단한다() {
    assertThat(
            jdbc.queryForList(
                """
                select conrelid::regclass::text || ':' || confdeltype::text
                from pg_constraint
                where contype = 'f'
                  and confrelid = 'public.trip_plans'::regclass
                  and confdeltype <> 'c'
                order by conrelid::regclass::text
                """,
                String.class))
        .as("trip_plans 직접 자식 FK는 모두 ON DELETE CASCADE여야 한다")
        .isEmpty();
    installRevisionCommandInputAggregate();
    installExternalFactReference();
    assertThat(count("schedule_revision_runs", "trip_plan_id", TRIP)).isOne();
    assertThat(count("compute_run_inputs", "trip_plan_id", TRIP)).isOne();
    store.deleteOwned(OWNER, TRIP);

    assertThat(count("trip_plans", "id", TRIP)).isZero();
    assertThat(count("trip_days", "trip_plan_id", TRIP)).isZero();
    assertThat(count("schedule_revision_runs", "trip_plan_id", TRIP)).isZero();
    assertThat(count("compute_run_inputs", "trip_plan_id", TRIP)).isZero();
    assertThat(count("tour_places", "id", PLACE)).isOne();
    assertThat(count("data_import_runs", "id", IMPORT_RUN)).isOne();
    assertThat(count("user_profiles", "id", OWNER)).isOne();
    assertCode(() -> store.deleteOwned(OWNER, TRIP), "TRIP_NOT_FOUND");

    insertTrip("draft");
    installActiveSchedule();
    jdbc.update("update public.trip_plans set status = 'live' where id = ?", TRIP);
    assertCode(() -> store.deleteOwned(OWNER, TRIP), "TRIP_DELETE_CONFLICT");
    jdbc.update("update public.trip_plans set status = 'draft' where id = ?", TRIP);
    insertQueuedGenerationRun();
    assertCode(() -> store.deleteOwned(OWNER, TRIP), "TRIP_DELETE_CONFLICT");
    assertThat(count("trip_plans", "id", TRIP)).isOne();
    assertCode(() -> store.deleteOwned(OTHER, TRIP), "TRIP_NOT_FOUND");

    jdbc.update(
        "update public.itinerary_generation_runs set status = 'failed' where trip_plan_id = ?",
        TRIP);
    jdbc.update("update public.trip_plans set status = 'failed' where id = ?", TRIP);
    store.deleteOwned(OWNER, TRIP);
    assertThat(count("trip_plans", "id", TRIP)).isZero();

    insertTrip("draft");
    installActiveSchedule();
    jdbc.update("update public.trip_plans set status = 'completed' where id = ?", TRIP);
    store.deleteOwned(OWNER, TRIP);
    assertThat(count("trip_plans", "id", TRIP)).as("completed").isZero();

    insertTrip("cancelled");
    store.deleteOwned(OWNER, TRIP);
    assertThat(count("trip_plans", "id", TRIP)).as("cancelled").isZero();
  }

  private String updateAfter(CountDownLatch start, String value) throws InterruptedException {
    start.await();
    try {
      store.updateOwned(record(title(value), 1, NOW));
      return "success";
    } catch (TripException failure) {
      return failure.code();
    }
  }

  private String deleteAfter(CountDownLatch start) throws InterruptedException {
    start.await();
    try {
      store.deleteOwned(OWNER, TRIP);
      return "success";
    } catch (TripException failure) {
      return failure.code();
    }
  }

  private void insertOwner(UUID id, String email) {
    jdbc.update("insert into auth.users (id, email) values (?, ?)", id, email);
    jdbc.update("insert into public.user_profiles (id, email) values (?, ?)", id, email);
  }

  private void insertTrip(String status) {
    jdbc.update(
        """
        insert into public.trip_plans
          (id, user_id, public_token, title, status, start_date, end_date,
           timezone, user_pace, source_mode, data_version)
        values (?, ?, ?, '제주 여행', ?, '2026-09-01', '2026-09-03',
                'Asia/Seoul', 'normal', 'fixture', 'issue45-v1')
        """,
        TRIP,
        OWNER,
        "issue45-trip-token-" + status,
        status);
    jdbc.update(
        "insert into public.trip_transport_modes (trip_plan_id, transport_mode, priority,"
            + " is_primary) values (?, 'public_transit', 1, true)",
        TRIP);
    for (int index = 0; index < 3; index++) {
      jdbc.update(
          "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, ?, ?)",
          UUID.nameUUIDFromBytes(
              ("issue45-day-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          TRIP,
          index + 1,
          java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index)));
    }
  }

  private void installDraftSchedule() {
    jdbc.update(
        "insert into public.trip_schedule_versions (id, trip_plan_id, version_no, status,"
            + " source_type) values (?, ?, 1, 'draft', 'initial')",
        VERSION,
        TRIP);
  }

  private void installActiveSchedule() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "insert into public.trip_schedule_versions (id, trip_plan_id, version_no, status,"
                      + " source_type) values (?, ?, 1, 'draft', 'initial')",
                  VERSION,
                  TRIP);
              for (int index = 0; index < 3; index++) {
                UUID dayId =
                    jdbc.queryForObject(
                        "select id from public.trip_days where trip_plan_id = ? and day_no = ?",
                        UUID.class,
                        TRIP,
                        index + 1);
                jdbc.update(
                    """
                    insert into public.trip_items (
                      id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
                      item_type, title, planned_start_at, planned_end_at, stay_minutes,
                      source, facts
                    ) values (?, ?, ?, ?, 1, 'custom', '일정 항목',
                              (?::date + time '09:00') at time zone 'Asia/Seoul',
                              (?::date + time '10:00') at time zone 'Asia/Seoul',
                              60, 'user_input',
                              '{}'::jsonb)
                    """,
                    UUID.nameUUIDFromBytes(
                        ("issue45-item-" + index)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    TRIP,
                    dayId,
                    VERSION,
                    java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index)),
                    java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index)));
              }
              jdbc.update(
                  "update public.trip_plans set status = 'planned', active_schedule_version_id = ?"
                      + " where id = ?",
                  VERSION,
                  TRIP);
              jdbc.update(
                  "update public.trip_schedule_versions set status = 'active', applied_at = now()"
                      + " where id = ? and trip_plan_id = ?",
                  VERSION,
                  TRIP);
            });
  }

  private void installRevisionCommandInputAggregate() {
    installActiveSchedule();
    UUID targetDay =
        jdbc.queryForObject(
            "select id from public.trip_days where trip_plan_id = ? and day_no = 1",
            UUID.class,
            TRIP);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var structuredInput = objectMapper.createObjectNode();
              structuredInput.put("targetDayId", targetDay.toString());
              structuredInput.putArray("affectedItemIds");
              structuredInput.putArray("instructionCodes").add("MOVE_ITEM");
              var snapshot =
                  commandInputCanonicalizer.canonicalize(
                      new CommandInputRequest(
                          new CommandInputParent.ScheduleRevision(REVISION_RUN),
                          "schedule_revision",
                          2,
                          "revision/v1",
                          "algorithm/v1",
                          structuredInput,
                          OWNER,
                          TRIP,
                          VERSION));
              jdbc.update(
                  """
                  insert into public.schedule_revision_runs (
                    id, owner_user_id, trip_plan_id, base_schedule_version_id,
                    target_trip_day_id, contract_version, algorithm_version,
                    idempotency_key, request_hash
                  ) values (?, ?, ?, ?, ?, 'revision/v1', 'algorithm/v1', ?, ?)
                  """,
                  REVISION_RUN,
                  OWNER,
                  TRIP,
                  VERSION,
                  targetDay,
                  UUID.fromString("45000000-0000-0000-0000-000000000109"),
                  snapshot.commandInputHash());
              commandInputRepository.save(snapshot);
              jdbc.update(
                  """
                  update public.schedule_revision_runs
                  set status = 'cancelled', failure_code = 'USER_CANCELLED',
                      completed_at = now(), next_attempt_at = null
                  where id = ?
                  """,
                  REVISION_RUN);
            });
  }

  private void installExternalFactReference() {
    jdbc.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_provider, source_service,
          source_operation, data_version, status, finished_at
        ) values (?, 'tour_api', 'TourAPI', 'tour-api', 'KorService2',
                  'areaBasedList2', 'issue45-v1', 'succeeded', now())
        """,
        IMPORT_RUN);
    jdbc.update(
        """
        insert into public.tour_places
          (id, external_place_id, name, normalized_name, category, location,
           source_provider, source_service)
        values (?, 'issue45-place', '외부 장소', '외부 장소', '관광지',
                ST_SetSRID(ST_MakePoint(126.5, 33.5), 4326)::geography,
                'fixture', 'issue45-test')
        """,
        PLACE);
    jdbc.update(
        "insert into public.trip_preferences"
            + " (trip_plan_id, start_place_id, arrival_region_code, departure_region_code)"
            + " values (?, ?, 'jeju-si', 'seogwipo-si')",
        TRIP,
        PLACE);
  }

  private void insertQueuedGenerationRun() {
    UUID dayId =
        jdbc.queryForObject(
            "select id from public.trip_days where trip_plan_id = ? and day_no = 1",
            UUID.class,
            TRIP);
    UUID run = UUID.fromString("45000000-0000-0000-0000-000000000120");
    var input = objectMapper.createObjectNode();
    input.put("targetDayId", dayId.toString());
    input.put("candidateCount", 3);
    input.put("refreshExternalFacts", false);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  """
                  insert into public.itinerary_generation_runs
                    (id,trip_plan_id,trip_day_id,base_schedule_version_id,status,structured_input,
                     contract_version,algorithm_version,idempotency_key,requested_by_user_id)
                  values (?,?,?,?,'queued',?::jsonb,'recommendation.v1','issue45-v1',?,?)
                  """,
                  run,
                  TRIP,
                  dayId,
                  VERSION,
                  input.toString(),
                  "issue45-generation-key",
                  OWNER);
              commandInputRepository.save(
                  commandInputCanonicalizer.canonicalize(
                      new CommandInputRequest(
                          new CommandInputParent.Generation(run),
                          "itinerary_generation",
                          2,
                          "recommendation.v1",
                          "issue45-v1",
                          input,
                          OWNER,
                          TRIP,
                          VERSION)));
            });
  }

  private TripUpdateRecord record(PatchTripCommand command, long revision, Instant updatedAt) {
    return new TripUpdateRecord(
        OWNER, TRIP, new TripExpectedRevision(TRIP, revision), command, ids(), updatedAt);
  }

  private static PatchTripCommand title(String title) {
    return command(
        TripPatchValue.present(title),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted());
  }

  private static PatchTripCommand pace(String pace) {
    return command(
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.present(pace),
        TripPatchValue.omitted());
  }

  private static PatchTripCommand modes(List<TripTransportMode> modes) {
    return command(
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.present(modes));
  }

  private static PatchTripCommand dates(LocalDate start, LocalDate end) {
    return command(
        TripPatchValue.omitted(),
        TripPatchValue.present(start),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.present(end));
  }

  private static PatchTripCommand command(
      TripPatchValue<String> title,
      TripPatchValue<LocalDate> start,
      TripPatchValue<String> pace,
      TripPatchValue<List<TripTransportMode>> modes) {
    return command(title, start, pace, modes, TripPatchValue.omitted());
  }

  private static PatchTripCommand command(
      TripPatchValue<String> title,
      TripPatchValue<LocalDate> start,
      TripPatchValue<String> pace,
      TripPatchValue<List<TripTransportMode>> modes,
      TripPatchValue<LocalDate> end) {
    return new PatchTripCommand(title, start, end, TripPatchValue.omitted(), pace, modes);
  }

  private static List<UUID> ids() {
    List<UUID> ids = new ArrayList<>(30);
    for (int index = 0; index < 30; index++) {
      ids.add(
          UUID.nameUUIDFromBytes(
              ("issue45-new-day-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    return ids;
  }

  private <T> T root(String column, Class<T> type) {
    return jdbc.queryForObject(
        "select " + column + " from public.trip_plans where id = ?", type, TRIP);
  }

  private String fingerprint() {
    return jdbc.queryForObject(
        """
        select concat_ws('|', revision, title, status, start_date, end_date,
          coalesce(active_schedule_version_id::text, ''),
          (select string_agg(concat(day_no, ':', trip_date), ',' order by day_no)
           from public.trip_days where trip_plan_id = p.id))
        from public.trip_plans p where id = ?
        """,
        String.class,
        TRIP);
  }

  private String calendarFingerprint() {
    return jdbc.queryForObject(
        """
        select concat_ws('|', revision, start_date, end_date,
          (select string_agg(concat(day_no, ':', trip_date), ',' order by day_no)
           from public.trip_days where trip_plan_id = p.id),
          (select string_agg(concat(event_type, ':', scheduled_at), ',' order by event_type)
           from public.trip_transport_events where trip_plan_id = p.id),
          (select string_agg(concat(place_id, ':', target_day_no), ',' order by place_id)
           from public.trip_place_preferences where trip_plan_id = p.id))
        from public.trip_plans p where id = ?
        """,
        String.class,
        TRIP);
  }

  private int count(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "select count(*) from public." + table + " where " + column + " = ?", Integer.class, id);
  }

  private static String sqlState(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof java.sql.SQLException sqlFailure) return sqlFailure.getSQLState();
      current = current.getCause();
    }
    return null;
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(code);
  }
}
