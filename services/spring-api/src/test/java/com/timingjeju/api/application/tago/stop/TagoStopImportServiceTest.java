package com.timingjeju.api.application.tago.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoStopImportServiceTest {
  private static final UUID RUN = UUID.fromString("35000000-0000-0000-0000-000000000001");
  private static final UUID OWNER = UUID.fromString("35000000-0000-0000-0000-000000000002");
  private static final ImportRunLease LEASE = new ImportRunLease(RUN, OWNER, 1);

  @Test
  void city_discovery와_모든_station_page_snapshot을_검증한_뒤_한번만_commit한다() {
    FakeSource source = new FakeSource();
    FakeParser parser = new FakeParser();
    parser.pages.add(page(1, 101, stations(100, 0)));
    parser.pages.add(page(2, 101, stations(1, 100)));
    FakeSession session = new FakeSession(false);
    FakeSnapshots snapshots = new FakeSnapshots();
    FakeCommitter committer = new FakeCommitter();

    TagoStopImportResult result =
        new TagoStopImportService(source, parser, session, snapshots, committer)
            .sync(new TagoStopImportCommand("issue-35-full"));

    assertThat(result.replayed()).isFalse();
    assertThat(result.cityCode()).isEqualTo("39");
    assertThat(result.stationCount()).isEqualTo(101);
    assertThat(source.stationPages).containsExactly(1, 2);
    assertThat(snapshots.savedKinds).containsExactly("city:1", "station:1", "station:2");
    assertThat(committer.commands)
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.cityCode()).isEqualTo(new TagoCityCode("39", "제주특별자치도"));
              assertThat(command.stations()).hasSize(101);
              assertThat(command.pages()).hasSize(3);
              assertThat(command.expectedCheckpointVersion()).isEqualTo(7);
            });
  }

  @Test
  void partial_page와_cross_page_duplicate는_normalized_checkpoint_commit없이_fail한다() {
    for (List<TagoStationPage> pages :
        List.of(
            List.of(page(1, 101, stations(1, 0))),
            List.of(page(1, 101, stations(100, 0)), page(2, 101, stations(1, 0))))) {
      FakeSource source = new FakeSource();
      FakeParser parser = new FakeParser();
      parser.pages.addAll(pages);
      FakeSession session = new FakeSession(false);
      FakeCommitter committer = new FakeCommitter();

      assertThatThrownBy(
              () ->
                  new TagoStopImportService(source, parser, session, new FakeSnapshots(), committer)
                      .sync(new TagoStopImportCommand("issue-35-partial-" + pages.size())))
          .isInstanceOf(TagoStopImportException.class);

      assertThat(committer.commands).isEmpty();
      assertThat(session.failed).isTrue();
    }
  }

  @Test
  void succeeded_replay는_provider와_snapshot과_commit을_호출하지_않는다() {
    FakeSource source = new FakeSource();
    FakeParser parser = new FakeParser();
    FakeSession session = new FakeSession(true);
    FakeSnapshots snapshots = new FakeSnapshots();
    FakeCommitter committer = new FakeCommitter();

    TagoStopImportResult result =
        new TagoStopImportService(source, parser, session, snapshots, committer)
            .sync(new TagoStopImportCommand("issue-35-replay"));

    assertThat(result.replayed()).isTrue();
    assertThat(result.stationCount()).isEqualTo(9);
    assertThat(source.cityCalls).isZero();
    assertThat(snapshots.savedKinds).isEmpty();
    assertThat(committer.commands).isEmpty();
  }

  private static TagoStationPage page(int pageNo, int total, List<TagoStation> stations) {
    return new TagoStationPage(pageNo, 100, total, stations);
  }

  private static List<TagoStation> stations(int count, int offset) {
    List<TagoStation> stations = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      stations.add(
          new TagoStation(
              "39",
              "JEP" + (offset + index),
              Integer.toString(offset + index),
              "정류장 " + (offset + index),
              126.5,
              33.5));
    }
    return stations;
  }

  private static final class FakeSource implements TagoStopSource {
    private int cityCalls;
    private final List<Integer> stationPages = new ArrayList<>();

    @Override
    public TagoStopSourceResponse fetchCityCodes() {
      cityCalls++;
      return response("city");
    }

    @Override
    public TagoStopSourceResponse fetchStations(String cityCode, int pageNo) {
      stationPages.add(pageNo);
      return response("station-" + pageNo);
    }
  }

  private static TagoStopSourceResponse response(String value) {
    return new TagoStopSourceResponse(
        value.getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
  }

  private static final class FakeParser implements TagoStopPayloadParser {
    private final List<TagoStationPage> pages = new ArrayList<>();

    @Override
    public List<TagoCityCode> parseCityCodes(SnapshotPayloadFormat format, byte[] payload) {
      return List.of(new TagoCityCode("39", "제주특별자치도"));
    }

    @Override
    public String discoverJejuCityCode(SnapshotPayloadFormat format, byte[] payload) {
      return "39";
    }

    @Override
    public TagoStationPage parseStations(
        SnapshotPayloadFormat format, byte[] payload, String expectedCityCode, int expectedPageNo) {
      return pages.removeFirst();
    }
  }

  private static final class FakeSession implements TagoStopImportSession {
    private final boolean replay;
    private boolean failed;

    private FakeSession(boolean replay) {
      this.replay = replay;
    }

    @Override
    public StartedTagoStopImport start(TagoStopImportCommand command) {
      return replay
          ? new StartedTagoStopImport(
              LEASE, true, 8, "39", new ImportRunCounts(9, 3, 4, 2, 3, 0, 0, 0))
          : new StartedTagoStopImport(LEASE, false, 7, null, ImportRunCounts.zero());
    }

    @Override
    public void fail(ImportRunLease lease) {
      failed = true;
    }
  }

  private static final class FakeSnapshots implements TagoStopSnapshotGateway {
    private final List<String> savedKinds = new ArrayList<>();

    @Override
    public SavedTagoStopPage saveCity(UUID runId, TagoStopSourceResponse response) {
      savedKinds.add("city:1");
      return saved(response, 0);
    }

    @Override
    public SavedTagoStopPage saveStations(
        UUID runId, String cityCode, int pageNo, TagoStopSourceResponse response) {
      savedKinds.add("station:" + pageNo);
      return saved(response, pageNo);
    }

    @Override
    public void markParsed(SavedTagoStopPage page) {}

    @Override
    public void markRejected(SavedTagoStopPage page) {}

    private static SavedTagoStopPage saved(TagoStopSourceResponse response, int pageNo) {
      return new SavedTagoStopPage(
          response,
          pageNo,
          UUID.nameUUIDFromBytes(("snapshot-" + pageNo).getBytes(StandardCharsets.UTF_8)),
          "a".repeat(64),
          Instant.parse("2026-08-16T00:00:00Z"),
          false,
          SnapshotStatus.RECEIVED);
    }
  }

  private static final class FakeCommitter implements TagoStopImportCommitter {
    private final List<TagoStopCommitCommand> commands = new ArrayList<>();

    @Override
    public TagoStopCommitResult commit(TagoStopCommitCommand command) {
      commands.add(command);
      return new TagoStopCommitResult(
          new ImportRunCounts(
              command.stations().size(),
              command.pages().size(),
              command.stations().size(),
              0,
              0,
              0,
              0,
              0),
          command.expectedCheckpointVersion() + 1);
    }
  }
}
