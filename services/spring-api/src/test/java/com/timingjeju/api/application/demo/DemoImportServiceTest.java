package com.timingjeju.api.application.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.tourapi.detail.DetailCommonParser;
import com.timingjeju.api.application.tourapi.detail.DetailCommonSource;
import com.timingjeju.api.application.tourapi.detail.DetailIntroParser;
import com.timingjeju.api.application.tourapi.detail.DetailIntroSource;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailCommon;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailIntro;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailRepository;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailUpsertResult;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportCommand;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportException;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportService;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemSyncResult;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportCommand;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportService;
import com.timingjeju.api.application.tourapi.image.PlaceImageSyncResult;
import com.timingjeju.api.application.tourapi.place.PlaceListImportCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListImportResult;
import com.timingjeju.api.application.tourapi.place.PlaceListImportService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class DemoImportServiceTest {

  @Test
  void import_tour_api는_아이덴티티키로_importer를_요청한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);

    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            mock(DetailCommonSource.class),
            mock(DetailCommonParser.class),
            mock(DetailIntroSource.class),
            mock(DetailIntroParser.class),
            mock(PlaceDetailRepository.class),
            mock(DetailItemImportService.class),
            mock(PlaceImageImportService.class),
            Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper());
    ArgumentCaptor<PlaceListImportCommand> commandCaptor =
        ArgumentCaptor.forClass(PlaceListImportCommand.class);
    UUID listRunId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 3, 2, 1, 0, Map.of(), false));
    when(reader.candidates(eq(listRunId), anyString(), anyString(), anyString()))
        .thenReturn(List.of());

    DemoImportResult result = service.importTourPlaces();

    verify(importer).importPlaces(commandCaptor.capture());
    assertThat(result.runId()).isEqualTo(listRunId);
    assertThat(result.inserted()).isEqualTo(3);
    assertThat(result.updated()).isEqualTo(2);
    assertThat(result.replayed()).isFalse();
    assertThat(commandCaptor.getValue().idempotencyKey()).startsWith("demo-");
  }

  @Test
  void latestStorage는_reader에서_가져온_view를_그대로_반환한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    DemoStorageView expected =
        new DemoStorageView(
            List.of(
                new DemoRunRow(
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "tour_api",
                    "areaBasedList2",
                    "succeeded",
                    3,
                    2,
                    Instant.EPOCH)),
            List.of(
                new DemoSnapshotRow(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    "areaBasedList2",
                    "parsed",
                    1234L)),
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주도 제주시",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    when(reader.latest()).thenReturn(expected);
    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            mock(DetailCommonSource.class),
            mock(DetailCommonParser.class),
            mock(DetailIntroSource.class),
            mock(DetailIntroParser.class),
            mock(PlaceDetailRepository.class),
            mock(DetailItemImportService.class),
            mock(PlaceImageImportService.class),
            Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper());

    DemoStorageView actual = service.latestStorage();

    assertThat(actual).isSameAs(expected);
  }

  @Test
  void content_type_12_32_39_장소만_candidates_조회_된다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);

    UUID listRunId = UUID.fromString("ccccccc1-1111-4111-b111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 1, 0, 0, 0, Map.of(), false));

    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            mock(DetailCommonSource.class),
            mock(DetailCommonParser.class),
            mock(DetailIntroSource.class),
            mock(DetailIntroParser.class),
            mock(PlaceDetailRepository.class),
            mock(DetailItemImportService.class),
            mock(PlaceImageImportService.class),
            Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper());

    when(reader.candidates(eq(listRunId), eq("12"), eq("32"), eq("39"))).thenReturn(List.of());
    service.importTourPlaces();

    verify(reader).candidates(listRunId, "12", "32", "39");
  }

  @Test
  void 상세_수집_실행은_별도_런을_오케스트레이션한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC);
    DetailCommonSource commonSource =
        contentId ->
            new DetailSourceResponse(
                "{}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
    DetailCommonParser commonParser =
        (format, payload) ->
            new PlaceDetailCommon("10001", "12", null, null, null, null, clock.instant());
    DetailIntroSource introSource =
        (contentId, contentTypeId) ->
            new DetailSourceResponse(
                "{}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
    DetailIntroParser introParser =
        (format, payload) ->
            new PlaceDetailIntro(
                "10001", "12", null, null, null, null, null, null, null, null, null, Map.of());
    PlaceDetailRepository placeDetailRepository = mock(PlaceDetailRepository.class);
    DetailItemImportService detailItemImportService = mock(DetailItemImportService.class);
    PlaceImageImportService detailImageImportService = mock(PlaceImageImportService.class);

    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            commonSource,
            commonParser,
            introSource,
            introParser,
            placeDetailRepository,
            detailItemImportService,
            detailImageImportService,
            clock,
            new ObjectMapper());

    UUID listRunId = UUID.fromString("aaaaaaa2-1111-4111-b111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 1, 0, 0, 0, Map.of(), false));
    when(reader.candidates(eq(listRunId), anyString(), anyString(), anyString()))
        .thenReturn(
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)));

    when(reader.sweepStats(any(UUID.class), eq("detailInfo2")))
        .thenReturn(new DemoSweepStats(3, 2));
    when(reader.sweepStats(any(UUID.class), eq("detailImage2")))
        .thenReturn(new DemoSweepStats(2, 1));
    when(reader.sweepStats(any(UUID.class), anyString())).thenReturn(DemoSweepStats.empty());
    String snapshotFingerprint = "b".repeat(64);

    when(snapshotService.save(any(SnapshotSaveCommand.class)))
        .thenReturn(
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED),
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED),
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000003"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED),
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000004"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED));

    when(runService.start(any(ImportRunStartCommand.class)))
        .thenReturn(
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000001"), UUID.randomUUID(), 1),
                false),
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000002"), UUID.randomUUID(), 1),
                false),
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000003"), UUID.randomUUID(), 1),
                false),
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000004"), UUID.randomUUID(), 1),
                false));

    when(placeDetailRepository.upsert(any())).thenReturn(PlaceDetailUpsertResult.insertedResult());
    when(detailItemImportService.importItems(any(DetailItemImportCommand.class)))
        .thenReturn(new DetailItemSyncResult(2, 0, 1, 0, 1));
    when(detailImageImportService.importImages(any(PlaceImageImportCommand.class)))
        .thenReturn(new PlaceImageSyncResult(1, 1, 0, 1, 0));

    service.importTourPlaces();

    ArgumentCaptor<ImportRunStartCommand> runCommands =
        ArgumentCaptor.forClass(ImportRunStartCommand.class);
    verify(runService, times(4)).start(runCommands.capture());

    assertThat(runCommands.getAllValues().get(0).scope().operation()).isEqualTo("detailCommon2");
    assertThat(runCommands.getAllValues().get(1).scope().operation()).isEqualTo("detailIntro2");
    assertThat(runCommands.getAllValues().get(2).scope().operation()).isEqualTo("detailInfo2");
    assertThat(runCommands.getAllValues().get(3).scope().operation()).isEqualTo("detailImage2");
  }

  @Test
  void detail_info_이미지_카운트는_스윕_통계로_적재카운트를_표시한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    DetailItemImportService detailItemImportService = mock(DetailItemImportService.class);
    PlaceImageImportService detailImageImportService = mock(PlaceImageImportService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC);

    DetailCommonSource commonSource =
        contentId ->
            new DetailSourceResponse(
                "{}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
    DetailCommonParser commonParser =
        (format, payload) ->
            new PlaceDetailCommon("10001", "12", null, null, null, null, clock.instant());
    DetailIntroSource introSource =
        (contentId, contentTypeId) ->
            new DetailSourceResponse(
                "{}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
    DetailIntroParser introParser =
        (format, payload) ->
            new PlaceDetailIntro(
                "10001", "12", null, null, null, null, null, null, null, null, null, Map.of());
    PlaceDetailRepository placeDetailRepository = mock(PlaceDetailRepository.class);

    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            commonSource,
            commonParser,
            introSource,
            introParser,
            placeDetailRepository,
            detailItemImportService,
            detailImageImportService,
            clock,
            new ObjectMapper());

    UUID listRunId = UUID.fromString("bbbbbbb3-1111-4111-b111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 1, 0, 0, 0, Map.of(), false));
    when(reader.candidates(eq(listRunId), anyString(), anyString(), anyString()))
        .thenReturn(
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)));
    when(reader.sweepStats(any(), anyString()))
        .thenAnswer(
            invocation -> {
              String operation = invocation.getArgument(1, String.class);
              return switch (operation) {
                case "detailInfo2" -> new DemoSweepStats(10, 4);
                case "detailImage2" -> new DemoSweepStats(11, 5);
                default -> DemoSweepStats.empty();
              };
            });
    String snapshotFingerprint = "c".repeat(64);
    when(snapshotService.save(any(SnapshotSaveCommand.class)))
        .thenReturn(
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED),
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED),
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000003"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED),
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000004"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED));
    when(runService.start(any(ImportRunStartCommand.class)))
        .thenReturn(
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000001"), UUID.randomUUID(), 1),
                false),
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000002"), UUID.randomUUID(), 1),
                false),
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000003"), UUID.randomUUID(), 1),
                false),
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000004"), UUID.randomUUID(), 1),
                false));
    when(placeDetailRepository.upsert(any())).thenReturn(PlaceDetailUpsertResult.insertedResult());

    when(detailItemImportService.importItems(any(DetailItemImportCommand.class)))
        .thenReturn(new DetailItemSyncResult(2, 0, 1, 3, 1));
    when(detailImageImportService.importImages(any(PlaceImageImportCommand.class)))
        .thenReturn(new PlaceImageSyncResult(1, 0, 0, 1, 1));

    service.importTourPlaces();

    verify(runService)
        .succeed(any(ImportRunLease.class), eq(new ImportRunCounts(10, 4, 2, 0, 1, 0, 1, 3)));
    verify(runService)
        .succeed(any(ImportRunLease.class), eq(new ImportRunCounts(11, 5, 1, 0, 0, 0, 1, 1)));
  }

  @Test
  void detail_실패_1개를_포함해도_후보_각_단계_런은_시도되고_부분결과가_반환된다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    DetailItemImportService detailItemImportService = mock(DetailItemImportService.class);
    PlaceImageImportService detailImageImportService = mock(PlaceImageImportService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC);
    PlaceDetailRepository placeDetailRepository = mock(PlaceDetailRepository.class);
    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            contentId ->
                new DetailSourceResponse(
                    ("{\"contentId\":\"" + contentId + "\"}").getBytes(StandardCharsets.UTF_8),
                    SnapshotPayloadFormat.JSON),
            (format, payload) ->
                new PlaceDetailCommon(
                    new String(payload, StandardCharsets.UTF_8)
                        .replaceAll(".*\\\"contentId\\\":\\\"(.*?)\\\".*", "$1"),
                    "12",
                    null,
                    null,
                    null,
                    null,
                    clock.instant()),
            (contentId, contentTypeId) ->
                new DetailSourceResponse(
                    ("{\"contentId\":\"" + contentId + "\"}").getBytes(StandardCharsets.UTF_8),
                    SnapshotPayloadFormat.JSON),
            (format, payload) ->
                new PlaceDetailIntro(
                    new String(payload, StandardCharsets.UTF_8)
                        .replaceAll(".*\\\"contentId\\\":\\\"(.*?)\\\".*", "$1"),
                    "12",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of()),
            placeDetailRepository,
            detailItemImportService,
            detailImageImportService,
            clock,
            new ObjectMapper());
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(UUID.randomUUID(), 1, 1, 0, 0, 0, Map.of(), false));
    when(reader.candidates(any(UUID.class), anyString(), anyString(), anyString()))
        .thenReturn(List.of(place("10001"), place("10002"), place("10003")));
    when(reader.sweepStats(any(UUID.class), anyString())).thenReturn(DemoSweepStats.empty());

    String snapshotFingerprint = "d".repeat(64);
    when(snapshotService.save(any(SnapshotSaveCommand.class)))
        .thenReturn(
            new SnapshotSaveResult(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                snapshotFingerprint,
                "h",
                false,
                clock.instant(),
                SnapshotStatus.RECEIVED));
    when(reader.sweepStats(any(UUID.class), anyString())).thenReturn(DemoSweepStats.empty());

    when(detailItemImportService.importItems(any(DetailItemImportCommand.class)))
        .thenAnswer(
            invocation -> {
              DetailItemImportCommand command = invocation.getArgument(0);
              if (command.contentId().equals("10002")) {
                throw DetailItemImportException.invalidResponse();
              }
              return new DetailItemSyncResult(1, 0, 0, 1, 0);
            });
    when(detailImageImportService.importImages(any(PlaceImageImportCommand.class)))
        .thenReturn(new PlaceImageSyncResult(1, 1, 0, 0, 0));
    when(placeDetailRepository.upsert(any())).thenReturn(PlaceDetailUpsertResult.insertedResult());
    AtomicInteger runSequence = new AtomicInteger(0);
    when(runService.start(any(ImportRunStartCommand.class)))
        .thenAnswer(
            invocation -> {
              int current = runSequence.incrementAndGet();
              return new ImportRunStartResult(
                  new ImportRunLease(
                      UUID.fromString(
                          String.format("30000000-0000-0000-0000-%012d", (current % 900 + 100))),
                      UUID.randomUUID(),
                      1L),
                  false);
            });

    DemoImportResult result = service.importTourPlaces();

    assertThat(result.selectedPlaceCount()).isEqualTo(3);
    assertThat(result.detailStageSucceeded()).isEqualTo(8);
    assertThat(result.detailStageFailed()).isEqualTo(1);
    verify(runService, times(12)).start(any(ImportRunStartCommand.class));
  }

  @Test
  void detail_common만_replay면_storageFailure로_실패한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    DetailCommonSource commonSource = mock(DetailCommonSource.class);
    DetailIntroSource introSource = mock(DetailIntroSource.class);
    DetailCommonParser commonParser = mock(DetailCommonParser.class);
    DetailIntroParser introParser = mock(DetailIntroParser.class);
    PlaceDetailRepository placeDetailRepository = mock(PlaceDetailRepository.class);
    DetailItemImportService detailItemImportService = mock(DetailItemImportService.class);
    PlaceImageImportService detailImageImportService = mock(PlaceImageImportService.class);
    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            commonSource,
            commonParser,
            introSource,
            introParser,
            placeDetailRepository,
            detailItemImportService,
            detailImageImportService,
            Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper());

    UUID listRunId = UUID.fromString("ccccccc4-1111-4111-b111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 1, 0, 0, 0, Map.of(), false));
    when(reader.candidates(eq(listRunId), anyString(), anyString(), anyString()))
        .thenReturn(
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)));
    ImportRunLease infoLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000003"), UUID.randomUUID(), 1L);
    ImportRunLease imageLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000004"), UUID.randomUUID(), 1L);
    ImportRunLease commonLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000001"), UUID.randomUUID(), 1L);
    when(runService.start(any(ImportRunStartCommand.class)))
        .thenReturn(new ImportRunStartResult(commonLease, true))
        .thenReturn(
            new ImportRunStartResult(
                new ImportRunLease(
                    UUID.fromString("30000000-0000-0000-0000-000000000002"), UUID.randomUUID(), 1L),
                false))
        .thenReturn(new ImportRunStartResult(infoLease, false))
        .thenReturn(new ImportRunStartResult(imageLease, false));
    when(detailItemImportService.importItems(any(DetailItemImportCommand.class)))
        .thenReturn(new DetailItemSyncResult(0, 0, 0, 0, 0));
    when(detailImageImportService.importImages(any(PlaceImageImportCommand.class)))
        .thenReturn(new PlaceImageSyncResult(0, 0, 0, 0, 0));
    when(reader.sweepStats(any(), anyString())).thenReturn(DemoSweepStats.empty());

    DemoImportResult result = service.importTourPlaces();
    verify(runService, times(4)).start(any(ImportRunStartCommand.class));
    verify(runService)
        .fail(any(ImportRunLease.class), eq(ImportRunFailure.INVALID_PROVIDER_RESPONSE));
    assertThat(result.detailStageFailed()).isEqualTo(1);
    verifyNoInteractions(snapshotService, commonSource, introSource);
  }

  @Test
  void detail_common_replay와_intro_non_replay는_intro_lease를_fail로_종료한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    DetailCommonSource commonSource = mock(DetailCommonSource.class);
    DetailIntroSource introSource = mock(DetailIntroSource.class);
    DetailCommonParser commonParser = mock(DetailCommonParser.class);
    DetailIntroParser introParser = mock(DetailIntroParser.class);
    PlaceDetailRepository placeDetailRepository = mock(PlaceDetailRepository.class);
    DetailItemImportService detailItemImportService = mock(DetailItemImportService.class);
    PlaceImageImportService detailImageImportService = mock(PlaceImageImportService.class);

    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            commonSource,
            commonParser,
            introSource,
            introParser,
            placeDetailRepository,
            detailItemImportService,
            detailImageImportService,
            Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper());

    UUID listRunId = UUID.fromString("ddddddd5-1111-4111-b111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 1, 0, 0, 0, Map.of(), false));
    when(reader.candidates(eq(listRunId), anyString(), anyString(), anyString()))
        .thenReturn(
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)));
    ImportRunLease commonLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000001"), UUID.randomUUID(), 1L);
    ImportRunLease introLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000002"), UUID.randomUUID(), 1L);
    ImportRunLease infoLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000003"), UUID.randomUUID(), 1L);
    ImportRunLease imageLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000004"), UUID.randomUUID(), 1L);
    when(runService.start(any(ImportRunStartCommand.class)))
        .thenReturn(new ImportRunStartResult(commonLease, true))
        .thenReturn(new ImportRunStartResult(introLease, false))
        .thenReturn(new ImportRunStartResult(infoLease, false))
        .thenReturn(new ImportRunStartResult(imageLease, false));
    when(detailItemImportService.importItems(any(DetailItemImportCommand.class)))
        .thenReturn(new DetailItemSyncResult(0, 0, 0, 0, 0));
    when(detailImageImportService.importImages(any(PlaceImageImportCommand.class)))
        .thenReturn(new PlaceImageSyncResult(0, 0, 0, 0, 0));
    when(reader.sweepStats(any(), anyString())).thenReturn(DemoSweepStats.empty());

    DemoImportResult result = service.importTourPlaces();
    verify(runService).fail(introLease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
    verify(runService, times(0)).succeed(eq(introLease), any(ImportRunCounts.class));
    assertThat(result.detailStageFailed()).isEqualTo(1);
    verifyNoInteractions(snapshotService, commonSource, introSource);
  }

  @Test
  void detail_common_non_replay_intro_replay면_common_lease를_fail로_종료한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    DetailCommonSource commonSource = mock(DetailCommonSource.class);
    DetailIntroSource introSource = mock(DetailIntroSource.class);
    DetailCommonParser commonParser = mock(DetailCommonParser.class);
    DetailIntroParser introParser = mock(DetailIntroParser.class);
    PlaceDetailRepository placeDetailRepository = mock(PlaceDetailRepository.class);
    DetailItemImportService detailItemImportService = mock(DetailItemImportService.class);
    PlaceImageImportService detailImageImportService = mock(PlaceImageImportService.class);

    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            commonSource,
            commonParser,
            introSource,
            introParser,
            placeDetailRepository,
            detailItemImportService,
            detailImageImportService,
            Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper());

    UUID listRunId = UUID.fromString("ddddddd6-1111-4111-b111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 1, 0, 0, 0, Map.of(), false));
    when(reader.candidates(eq(listRunId), anyString(), anyString(), anyString()))
        .thenReturn(
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)));
    ImportRunLease commonLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000011"), UUID.randomUUID(), 1L);
    ImportRunLease introLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000012"), UUID.randomUUID(), 1L);
    ImportRunLease infoLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000013"), UUID.randomUUID(), 1L);
    ImportRunLease imageLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000014"), UUID.randomUUID(), 1L);
    when(runService.start(any(ImportRunStartCommand.class)))
        .thenReturn(new ImportRunStartResult(commonLease, false))
        .thenReturn(new ImportRunStartResult(introLease, true))
        .thenReturn(new ImportRunStartResult(infoLease, false))
        .thenReturn(new ImportRunStartResult(imageLease, false));
    when(detailItemImportService.importItems(any(DetailItemImportCommand.class)))
        .thenReturn(new DetailItemSyncResult(0, 0, 0, 0, 0));
    when(detailImageImportService.importImages(any(PlaceImageImportCommand.class)))
        .thenReturn(new PlaceImageSyncResult(0, 0, 0, 0, 0));
    when(reader.sweepStats(any(), anyString())).thenReturn(DemoSweepStats.empty());

    DemoImportResult result = service.importTourPlaces();
    verify(runService).fail(commonLease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
    assertThat(result.detailStageFailed()).isEqualTo(1);
    verifyNoInteractions(snapshotService, commonSource, introSource);
    verify(runService, times(0)).succeed(eq(commonLease), any(ImportRunCounts.class));
  }

  @Test
  void detail_common_요청_실패면_이미_시작한_intro_lease도_fail로_종료한다() {
    PlaceListImportService importer = mock(PlaceListImportService.class);
    DemoStorageReader reader = mock(DemoStorageReader.class);
    ImportRunLifecycleService runService = mock(ImportRunLifecycleService.class);
    SnapshotStoreService snapshotService = mock(SnapshotStoreService.class);
    DetailCommonSource commonSource = mock(DetailCommonSource.class);
    DetailIntroSource introSource = mock(DetailIntroSource.class);
    DetailCommonParser commonParser = mock(DetailCommonParser.class);
    DetailIntroParser introParser = mock(DetailIntroParser.class);
    PlaceDetailRepository placeDetailRepository = mock(PlaceDetailRepository.class);
    DetailItemImportService detailItemImportService = mock(DetailItemImportService.class);
    PlaceImageImportService detailImageImportService = mock(PlaceImageImportService.class);

    DemoImportService service =
        new DemoImportService(
            importer,
            reader,
            runService,
            snapshotService,
            commonSource,
            commonParser,
            introSource,
            introParser,
            placeDetailRepository,
            detailItemImportService,
            detailImageImportService,
            Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper());

    UUID listRunId = UUID.fromString("eeeeeee8-1111-4111-b111-111111111111");
    when(importer.importPlaces(any(PlaceListImportCommand.class)))
        .thenReturn(new PlaceListImportResult(listRunId, 1, 1, 0, 0, 0, Map.of(), false));
    when(reader.candidates(eq(listRunId), anyString(), anyString(), anyString()))
        .thenReturn(
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)));

    ImportRunLease commonLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000099"), UUID.randomUUID(), 1L);
    ImportRunLease introLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000100"), UUID.randomUUID(), 1L);
    ImportRunLease infoLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000101"), UUID.randomUUID(), 1L);
    ImportRunLease imageLease =
        new ImportRunLease(
            UUID.fromString("30000000-0000-0000-0000-000000000102"), UUID.randomUUID(), 1L);
    when(runService.start(any(ImportRunStartCommand.class)))
        .thenReturn(new ImportRunStartResult(commonLease, false))
        .thenReturn(new ImportRunStartResult(introLease, false))
        .thenReturn(new ImportRunStartResult(infoLease, false))
        .thenReturn(new ImportRunStartResult(imageLease, false));
    when(commonSource.fetch("10001")).thenThrow(PlaceDetailImportException.invalidResponse());
    when(detailItemImportService.importItems(any(DetailItemImportCommand.class)))
        .thenReturn(new DetailItemSyncResult(0, 0, 0, 0, 0));
    when(detailImageImportService.importImages(any(PlaceImageImportCommand.class)))
        .thenReturn(new PlaceImageSyncResult(0, 0, 0, 0, 0));
    when(reader.sweepStats(any(), anyString())).thenReturn(DemoSweepStats.empty());

    DemoImportResult result = service.importTourPlaces();
    verify(runService).fail(commonLease, ImportRunFailure.PARSE_REJECTED);
    verify(runService).fail(introLease, ImportRunFailure.PARSE_REJECTED);
    assertThat(result.detailStageFailed()).isEqualTo(1);
    verify(runService, times(0)).succeed(eq(commonLease), any(ImportRunCounts.class));
    verify(runService, times(0)).succeed(eq(introLease), any(ImportRunCounts.class));
    verifyNoInteractions(snapshotService);
  }

  private static DemoPlaceRow place(String contentId) {
    return new DemoPlaceRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        contentId,
        "12",
        "성산일출봉 " + contentId,
        "관광지",
        "제주",
        null,
        null,
        null,
        126.0,
        33.0);
  }
}
