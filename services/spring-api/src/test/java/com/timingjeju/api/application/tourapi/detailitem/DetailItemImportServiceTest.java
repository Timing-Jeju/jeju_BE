package com.timingjeju.api.application.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
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

  @Test
  void detailInfo를_한번_호출하고_검증된_batch와_lineage를_repository에_전달한다() {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> response(),
            (format, payload, id, type) -> page("100", "12", 1, 100, 1, items("1")),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    var result = service.importItems(new DetailItemImportCommand("100", "12", LINEAGE));

    assertThat(result.insertedCount()).isEqualTo(1);
    assertThat(repository.command.contentId()).isEqualTo("100");
    assertThat(repository.command.lineage()).isEqualTo(LINEAGE);
    assertThat(repository.command.observedAt()).isEqualTo(NOW);
  }

  @Test
  void 응답의_place나_content_type이_요청과_다르면_write전에_거부한다() {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> response(),
            (format, payload, id, type) -> page("other", "12", 1, 100, 1, items("1")),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", LINEAGE)))
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
            new TourApiDetailInfoParser(new ObjectMapper(), new DetailItemContentSanitizer()),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", LINEAGE)))
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
    pages.add(page("100", "12", 2, 100, 101, items("101")));
    List<Integer> requestedPages = new ArrayList<>();
    RecordingRepository repository = new RecordingRepository();
    var service =
        new DetailItemImportService(
            (id, type, pageNo) -> {
              requestedPages.add(pageNo);
              return response();
            },
            (format, payload, id, type) -> pages.remove(),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    service.importItems(new DetailItemImportCommand("100", "12", LINEAGE));

    assertThat(requestedPages).containsExactly(1, 2);
    assertThat(repository.command.batch().items()).hasSize(101);
    assertThat(repository.command.batch().items())
        .extracting(DetailItem::sequenceNo)
        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList());
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

  private static DetailItemPage page(
      String id, String type, int pageNo, int numOfRows, int totalCount, List<DetailItem> items) {
    return new DetailItemPage(id, type, pageNo, numOfRows, totalCount, items.size(), items);
  }

  private static List<DetailItem> manyItems(int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(index -> item(Integer.toString(index), index))
        .toList();
  }

  private static List<DetailItem> items(String key) {
    return List.of(item(key, 1));
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
            (format, payload, id, type) -> pages.remove(),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", LINEAGE)))
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
}
