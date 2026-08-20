package com.timingjeju.api.application.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.global.tourapi.detailitem.DetailItemContentSanitizer;
import com.timingjeju.api.global.tourapi.detailitem.TourApiDetailInfoParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class DetailItemImportServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-16T08:00:00Z");
  private static final DetailItemLineage LINEAGE =
      new DetailItemLineage("detailInfo2", "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
  private static final UUID RUN = LINEAGE.importRunId();

  @Test
  void detailInfo를_한번_호출하고_검증된_batch와_lineage를_repository에_전달한다() {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> response(),
            new RecordingSnapshotGateway(),
            (format, payload, id, type) -> page("100", "12", 1, 1, 1, items("1")),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    var result = service.importItems(new DetailItemImportCommand("100", "12", RUN));

    assertThat(result.insertedCount()).isEqualTo(1);
    assertThat(repository.command.contentId()).isEqualTo("100");
    assertThat(repository.command.sweep().importRunId()).isEqualTo(RUN);
    assertThat(repository.command.observedAt()).isEqualTo(NOW);
  }

  @Test
  void 응답의_place나_content_type이_요청과_다르면_write전에_거부한다() {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> response(),
            new RecordingSnapshotGateway(),
            (format, payload, id, type) -> page("other", "12", 1, 100, 1, items("1")),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", RUN)))
        .isInstanceOf(DetailItemImportException.class);
    assertThat(repository.command).isNull();
  }

  @Test
  void totalCount가_현재_page보다_큰_잘린_응답은_누락_item을_retire하기_전에_거부한다() {
    RecordingRepository repository = new RecordingRepository();
    byte[] truncatedPage =
        ("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":"
                + "{\"pageNo\":1,\"numOfRows\":100,\"totalCount\":101,\"items\":{\"item\":["
                + "{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\"1\","
                + "\"infoname\":\"첫 page 일부\"}]}}}}")
            .getBytes(StandardCharsets.UTF_8);
    var service =
        new DetailItemImportService(
            (id, type, pageNo) ->
                new DetailSourceResponse(truncatedPage, SnapshotPayloadFormat.JSON),
            new RecordingSnapshotGateway(),
            new TourApiDetailInfoParser(new ObjectMapper(), new DetailItemContentSanitizer()),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", RUN)))
        .isInstanceOf(DetailItemImportException.class);
    assertThat(repository.command).isNull();
  }

  @Test
  void 여러_page를_전부_모은_뒤_전역_응답순서로_한번만_sync한다() {
    Queue<DetailItemPage> pages = new ArrayDeque<>();
    List<DetailItem> firstPage = new ArrayList<>();
    for (int index = 1; index <= 100; index++) {
      firstPage.add(item(Integer.toString(index), index));
    }
    pages.add(page("100", "12", 1, 100, 101, firstPage));
    pages.add(page("100", "12", 2, 1, 101, items("101")));
    List<Integer> requestedPages = new ArrayList<>();
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> {
              requestedPages.add(pageNo);
              return response();
            },
            new RecordingSnapshotGateway(),
            (format, payload, id, type) -> pages.remove(),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    service.importItems(new DetailItemImportCommand("100", "12", RUN));

    assertThat(requestedPages).containsExactly(1, 2);
    assertThat(repository.command.batch().items()).hasSize(101);
    assertThat(repository.command.batch().items())
        .extracting(DetailItem::sequenceNo)
        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList());
  }

  @Test
  void 마지막_page가_부분page여도_요청한_총건수만큼_맞으면_통과한다() {
    Queue<DetailItemPage> pages = new ArrayDeque<>();
    pages.add(page("100", "12", 1, 100, 105, manyItems(100)));
    pages.add(page("100", "12", 2, 5, 105, manyItems(101, 5)));
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> response(),
            new RecordingSnapshotGateway(),
            (format, payload, id, type) -> pages.remove(),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    service.importItems(new DetailItemImportCommand("100", "12", RUN));

    assertThat(repository.command.batch().items()).hasSize(105);
    assertThat(repository.command.sweep().expectedTotal()).isEqualTo(105);
  }

  @Test
  void parser는_network_response가_아니라_snapshot_gateway가_보존한_exact_bytes만_소비한다() {
    RecordingRepository repository = new RecordingRepository();
    byte[] networkPayload = "network raw page".getBytes(StandardCharsets.UTF_8);
    byte[] storedPayload = "stored raw page".getBytes(StandardCharsets.UTF_8);
    RecordingSnapshotGateway snapshots = new RecordingSnapshotGateway(storedPayload);
    List<byte[]> parsedPayloads = new ArrayList<>();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) ->
                new DetailSourceResponse(networkPayload, SnapshotPayloadFormat.JSON),
            snapshots,
            (format, payload, id, type) -> {
              parsedPayloads.add(payload);
              return page("100", "12", 1, 1, 1, items("1"));
            },
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    service.importItems(new DetailItemImportCommand("100", "12", RUN));

    assertThat(snapshots.receivedPayloads).singleElement().isEqualTo(networkPayload);
    assertThat(parsedPayloads).singleElement().isEqualTo(storedPayload);
    assertThat(repository.command).isNotNull();
  }

  @Test
  void 중간_page_실패_totalCount_변경_또는_page간_key중복은_complete_batch_write전에_거부한다() {
    assertNoWrite(
        new ArrayDeque<>(List.of(page("100", "12", 1, 100, 101, manyItems(100)))), 2, true);
    assertNoWrite(
        new ArrayDeque<>(
            List.of(
                page("100", "12", 1, 100, 101, manyItems(100)),
                page("100", "12", 2, 100, 102, items("101")))),
        -1,
        false);
    assertNoWrite(
        new ArrayDeque<>(
            List.of(
                page("100", "12", 1, 100, 2, items("same")),
                page("100", "12", 2, 100, 2, items("same")))),
        -1,
        false);
  }

  @Test
  void rejected_terminal_snapshot_replay는_parse와_repository_write없이_거부한다() {
    RecordingRepository repository = new RecordingRepository();
    DetailInfoSnapshotGateway rejectedGateway =
        new DetailInfoSnapshotGateway() {
          @Override
          public SavedDetailInfoPage save(
              UUID importRunId,
              String contentId,
              String contentTypeId,
              int pageNo,
              DetailSourceResponse response) {
            return new SavedDetailInfoPage(
                response,
                pageNo,
                "e".repeat(64),
                NOW,
                new DetailItemLineage(
                    "detailInfo2", "a".repeat(64), UUID.randomUUID(), importRunId),
                true,
                SnapshotStatus.REJECTED);
          }

          @Override
          public void markParsed(SavedDetailInfoPage page) {
            throw new AssertionError("rejected replay를 parsed로 전이하면 안 됩니다.");
          }

          @Override
          public void markRejected(SavedDetailInfoPage page) {
            throw new AssertionError("terminal rejected replay를 다시 전이하면 안 됩니다.");
          }
        };
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> response(),
            rejectedGateway,
            (format, payload, id, type) -> {
              throw new AssertionError("rejected replay를 parse하면 안 됩니다.");
            },
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", RUN)))
        .isInstanceOf(DetailItemImportException.class);
    assertThat(repository.command).isNull();
  }

  private static DetailItemPage page(
      String id, String type, int pageNo, int numOfRows, int totalCount, List<DetailItem> items) {
    return new DetailItemPage(id, type, pageNo, numOfRows, totalCount, items.size(), items);
  }

  private static List<DetailItem> manyItems(int count) {
    return manyItems(1, count);
  }

  private static List<DetailItem> manyItems(int start, int count) {
    return java.util.stream.IntStream.rangeClosed(start, start + count - 1)
        .mapToObj(index -> item(Integer.toString(index), index))
        .toList();
  }

  private static List<DetailItem> items(String key) {
    return List.of(item(key, 1));
  }

  private static List<DetailItem> items(String startKey, int count) {
    return manyItems(Integer.parseInt(startKey), count);
  }

  private static DetailItem item(String key, int sequence) {
    return new DetailItem(
        "info",
        key,
        "안내 " + key,
        sequence,
        new DetailItemAttributes("tour-api.detailInfo2.info", 1, Map.of("infotext", "본문 " + key)));
  }

  private void assertNoWrite(
      Queue<DetailItemPage> pages, int failingSourcePage, boolean sourceFailure) {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> {
              if (sourceFailure && pageNo == failingSourcePage) {
                throw new IllegalStateException("provider page failure");
              }
              return response();
            },
            new RecordingSnapshotGateway(),
            (format, payload, id, type) -> pages.remove(),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", RUN)))
        .isInstanceOf(DetailItemImportException.class);
    assertThat(repository.command).isNull();
  }

  private static DetailSourceResponse response() {
    return new DetailSourceResponse(new byte[] {1}, SnapshotPayloadFormat.JSON);
  }

  private static final class RecordingRepository implements DetailItemRepository {
    private DetailItemSyncCommand command;

    @Override
    public DetailItemSyncResult sync(DetailItemSyncCommand command) {
      this.command = command;
      return new DetailItemSyncResult(1, 0, 0, 0, 0);
    }
  }

  private static final class RecordingSnapshotGateway implements DetailInfoSnapshotGateway {
    private final byte[] storedPayload;
    private final List<byte[]> receivedPayloads = new ArrayList<>();

    private RecordingSnapshotGateway() {
      this(null);
    }

    private RecordingSnapshotGateway(byte[] storedPayload) {
      this.storedPayload = storedPayload == null ? null : storedPayload.clone();
    }

    @Override
    public SavedDetailInfoPage save(
        UUID importRunId,
        String contentId,
        String contentTypeId,
        int pageNo,
        DetailSourceResponse response) {
      receivedPayloads.add(response.payload());
      byte[] persisted = storedPayload == null ? response.payload() : storedPayload.clone();
      String suffix = String.format("%012d", pageNo);
      DetailItemLineage lineage =
          new DetailItemLineage(
              "detailInfo2",
              Integer.toHexString(pageNo).repeat(64),
              UUID.fromString("28000000-0000-0000-0002-" + suffix),
              importRunId);
      return new SavedDetailInfoPage(
          new DetailSourceResponse(persisted, response.format()),
          pageNo,
          "e".repeat(64),
          NOW.plusSeconds(pageNo),
          lineage,
          false,
          com.timingjeju.api.application.snapshot.SnapshotStatus.RECEIVED);
    }

    @Override
    public void markParsed(SavedDetailInfoPage page) {}

    @Override
    public void markRejected(SavedDetailInfoPage page) {}
  }
}
