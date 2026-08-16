package com.timingjeju.api.application.tago.route;

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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoRouteImportServiceTest {
  private static final UUID RUN = UUID.fromString("36000000-0000-0000-0000-000000000001");
  private static final ImportRunLease LEASE =
      new ImportRunLease(RUN, UUID.fromString("36000000-0000-0000-0000-000000000002"), 1);

  @Test
  void fixture_101과_201의_두_방향과_연속_stop을_모두_검증한_뒤_한번만_commit한다() {
    FakeSource source = new FakeSource();
    FakeParser parser = new FakeParser();
    parser.routes =
        List.of(
            route("101", "101-A"),
            route("101", "101-B"),
            route("201", "201-A"),
            route("201", "201-B"));
    FakeCatalog catalog = new FakeCatalog(Set.of("STOP-1", "STOP-2"));
    FakeCommitter committer = new FakeCommitter();

    TagoRouteImportResult result =
        service(source, parser, catalog, committer, false)
            .sync(new TagoRouteImportCommand("issue-36-two-directions", List.of("101", "201")));

    assertThat(result.routeCount()).isEqualTo(4);
    assertThat(result.routeStopCount()).isEqualTo(8);
    assertThat(source.details).containsExactly("101-A", "101-B", "201-A", "201-B");
    assertThat(committer.commands)
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.routes())
                  .extracting(write -> write.route().directionKey())
                  .containsExactly("101-A", "101-B", "201-A", "201-B");
              assertThat(command.routeStops())
                  .allSatisfy(write -> assertThat(write.stop().stopSequence()).isPositive());
            });
  }

  @Test
  void missing_stop과_cross_city_provider_scope는_write전에_거부한다() {
    for (FakeCatalog catalog :
        List.of(new FakeCatalog(Set.of("STOP-1")), FakeCatalog.scopeMismatch())) {
      FakeCommitter committer = new FakeCommitter();
      assertThatThrownBy(
              () ->
                  service(new FakeSource(), new FakeParser(), catalog, committer, false)
                      .sync(
                          new TagoRouteImportCommand(
                              "issue-36-invalid-" + catalog.hashCode(), List.of("101"))))
          .isInstanceOf(TagoRouteImportException.class);
      assertThat(committer.commands).isEmpty();
    }
  }

  @Test
  void partial_page_duplicate_route와_duplicate_sequence는_commit과_checkpoint없이_실패한다() {
    for (Mode mode :
        List.of(
            Mode.PARTIAL, Mode.TOTAL_VARIATION, Mode.DUPLICATE_ROUTE, Mode.DUPLICATE_SEQUENCE)) {
      FakeParser parser = new FakeParser();
      parser.mode = mode;
      FakeCommitter committer = new FakeCommitter();
      assertThatThrownBy(
              () ->
                  service(
                          new FakeSource(),
                          parser,
                          new FakeCatalog(Set.of("STOP-1", "STOP-2")),
                          committer,
                          false)
                      .sync(new TagoRouteImportCommand("issue-36-" + mode, List.of("101"))))
          .isInstanceOf(TagoRouteImportException.class);
      assertThat(committer.commands).isEmpty();
    }
  }

  @Test
  void succeeded_replay는_provider_snapshot_catalog_commit을_호출하지_않는다() {
    FakeSource source = new FakeSource();
    FakeCatalog catalog = new FakeCatalog(Set.of());
    FakeCommitter committer = new FakeCommitter();
    TagoRouteImportResult result =
        service(source, new FakeParser(), catalog, committer, true)
            .sync(new TagoRouteImportCommand("issue-36-replay", List.of("101")));
    assertThat(result.replayed()).isTrue();
    assertThat(source.listCalls).isZero();
    assertThat(catalog.calls).isZero();
    assertThat(committer.commands).isEmpty();
  }

  private static TagoRouteImportService service(
      FakeSource source,
      FakeParser parser,
      FakeCatalog catalog,
      FakeCommitter committer,
      boolean replay) {
    return new TagoRouteImportService(
        source, parser, new FakeSession(replay), new FakeSnapshots(), catalog, committer);
  }

  private static TagoRoute route(String no, String id) {
    return new TagoRoute("39", id, no, "간선", "기점", "종점", id);
  }

  private enum Mode {
    OK,
    PARTIAL,
    TOTAL_VARIATION,
    DUPLICATE_ROUTE,
    DUPLICATE_SEQUENCE
  }

  private static final class FakeSource implements TagoRouteSource {
    int listCalls;
    final List<String> details = new ArrayList<>();

    public TagoRouteSourceResponse fetchRouteList(String city, String no, int page) {
      listCalls++;
      return response("list");
    }

    public TagoRouteSourceResponse fetchRouteDetail(String city, String id) {
      details.add(id);
      return response("detail");
    }

    public TagoRouteSourceResponse fetchRouteStops(String city, String id, int page) {
      return response("stops");
    }
  }

  private static final class FakeParser implements TagoRoutePayloadParser {
    List<TagoRoute> routes = List.of(route("101", "101-A"), route("101", "101-B"));
    Mode mode = Mode.OK;

    public TagoRoutePage parseRouteList(
        SnapshotPayloadFormat f, byte[] p, String city, String no, int page) {
      if (mode == Mode.TOTAL_VARIATION) {
        List<TagoRoute> found =
            java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> route(no, no + '-' + page + '-' + index))
                .toList();
        return new TagoRoutePage(page, 100, page == 1 ? 200 : 300, found);
      }
      List<TagoRoute> found =
          mode == Mode.DUPLICATE_ROUTE
              ? List.of(routes.getFirst(), routes.getFirst())
              : routes.stream().filter(r -> r.routeNo().equals(no)).toList();
      return new TagoRoutePage(1, 100, mode == Mode.PARTIAL ? 101 : found.size(), found);
    }

    public TagoRoute parseRouteDetail(SnapshotPayloadFormat f, byte[] p, String city, String id) {
      return routes.stream().filter(r -> r.externalRouteId().equals(id)).findFirst().orElseThrow();
    }

    public TagoRouteStopPage parseRouteStops(
        SnapshotPayloadFormat f, byte[] p, String city, String id, int page) {
      List<TagoRouteStop> stops =
          mode == Mode.DUPLICATE_SEQUENCE
              ? List.of(
                  new TagoRouteStop(city, id, "STOP-1", 1),
                  new TagoRouteStop(city, id, "STOP-2", 1))
              : List.of(
                  new TagoRouteStop(city, id, "STOP-1", 1),
                  new TagoRouteStop(city, id, "STOP-2", 2));
      return new TagoRouteStopPage(1, 100, stops.size(), stops);
    }
  }

  private static final class FakeSession implements TagoRouteImportSession {
    private final boolean replay;

    FakeSession(boolean replay) {
      this.replay = replay;
    }

    public StartedTagoRouteImport start(TagoRouteImportCommand command) {
      return replay
          ? new StartedTagoRouteImport(
              LEASE, true, 8, 4, 8, new ImportRunCounts(8, 4, 4, 0, 0, 0, 0, 0))
          : new StartedTagoRouteImport(LEASE, false, 7, 0, 0, ImportRunCounts.zero());
    }

    public void fail(ImportRunLease lease) {}
  }

  private static final class FakeSnapshots implements TagoRouteSnapshotGateway {
    public SavedTagoRoutePayload save(
        UUID run,
        String kind,
        String city,
        String route,
        int page,
        TagoRouteSourceResponse response) {
      return new SavedTagoRoutePayload(
          response,
          kind,
          page,
          UUID.randomUUID(),
          "a".repeat(64),
          Instant.parse("2026-08-16T00:00:00Z"),
          false,
          SnapshotStatus.RECEIVED);
    }

    public void markParsed(SavedTagoRoutePayload payload) {}

    public void markRejected(SavedTagoRoutePayload payload) {}
  }

  private static final class FakeCatalog implements TagoRouteStopCatalog {
    private final Set<String> ids;
    private final boolean mismatch;
    int calls;

    FakeCatalog(Set<String> ids) {
      this(ids, false);
    }

    FakeCatalog(Set<String> ids, boolean mismatch) {
      this.ids = ids;
      this.mismatch = mismatch;
    }

    static FakeCatalog scopeMismatch() {
      return new FakeCatalog(Set.of("STOP-1", "STOP-2"), true);
    }

    public void requireExisting(String provider, String service, String city, Set<String> nodeIds) {
      calls++;
      if (mismatch || !ids.containsAll(nodeIds)) throw TagoRouteImportException.stopScopeMismatch();
    }
  }

  private static final class FakeCommitter implements TagoRouteImportCommitter {
    final List<TagoRouteCommitCommand> commands = new ArrayList<>();

    public TagoRouteCommitResult commit(TagoRouteCommitCommand command) {
      commands.add(command);
      return new TagoRouteCommitResult(
          new ImportRunCounts(
              command.routeStops().size(),
              command.lineage().size(),
              command.routes().size(),
              0,
              0,
              0,
              0,
              0),
          command.expectedCheckpointVersion() + 1);
    }
  }

  private static TagoRouteSourceResponse response(String value) {
    return new TagoRouteSourceResponse(
        value.getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
  }
}
