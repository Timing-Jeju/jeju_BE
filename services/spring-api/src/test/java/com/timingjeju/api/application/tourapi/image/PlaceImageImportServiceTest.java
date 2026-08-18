package com.timingjeju.api.application.tourapi.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PlaceImageImportServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-16T09:00:00Z");
  private static final UUID RUN = UUID.fromString("29000000-0000-0000-0000-000000000001");

  @Test
  void 모든_page의_snapshot_exact_bytes를_parse한_뒤_전역순서로_한번만_sync한다() {
    Queue<PlaceImagePage> pages = new ArrayDeque<>();
    pages.add(page(1, 100, 101, images(1, 100)));
    pages.add(page(2, 1, 101, images(101, 101)));
    RecordingRepository repository = new RecordingRepository();
    RecordingGateway snapshots = new RecordingGateway("stored".getBytes());
    List<byte[]> parsed = new ArrayList<>();
    List<Integer> requested = new ArrayList<>();
    var service =
        new PlaceImageImportService(
            (contentId, pageNo) -> {
              requested.add(pageNo);
              return response("network");
            },
            snapshots,
            (format, payload, contentId) -> {
              parsed.add(payload);
              return pages.remove();
            },
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    var result = service.importImages(new PlaceImageImportCommand("100", "12", RUN));

    assertThat(requested).containsExactly(1, 2);
    assertThat(parsed).allSatisfy(payload -> assertThat(payload).isEqualTo("stored".getBytes()));
    assertThat(repository.command.batch().images()).hasSize(101);
    assertThat(repository.command.batch().images())
        .extracting(PlaceImage::displayOrder)
        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList());
    assertThat(result.insertedCount()).isEqualTo(101);
  }

  @Test
  void 마지막_page가_부분page여도_요청한_총건수만큼_맞으면_통과한다() {
    Queue<PlaceImagePage> pages = new ArrayDeque<>();
    pages.add(page(1, 100, 105, images(1, 100)));
    pages.add(page(2, 5, 105, images(101, 105)));
    RecordingRepository repository = new RecordingRepository();
    var service =
        new PlaceImageImportService(
            (contentId, pageNo) -> response("network"),
            new RecordingGateway("stored".getBytes()),
            (format, payload, contentId) -> pages.remove(),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    service.importImages(new PlaceImageImportCommand("100", "12", RUN));

    assertThat(repository.command.batch().images()).hasSize(105);
    assertThat(repository.command.sweep().expectedTotal()).isEqualTo(105);
  }

  @Test
  void partial_page_total변경_중간실패와_page간_duplicate는_repository전에_거부한다() {
    assertNoWrite(new ArrayDeque<>(List.of(page(1, 100, 101, images(1, 1)))), -1);
    assertNoWrite(
        new ArrayDeque<>(
            List.of(page(1, 100, 101, images(1, 100)), page(2, 100, 102, images(101, 101)))),
        -1);
    assertNoWrite(new ArrayDeque<>(List.of(page(1, 100, 101, images(1, 100)))), 2);
    assertNoWrite(
        new ArrayDeque<>(
            List.of(
                page(1, 100, 2, List.of(image("same", "https://img.example.test/1.jpg", 1))),
                page(2, 100, 2, List.of(image("same", "https://img.example.test/2.jpg", 1))))),
        -1);
  }

  @Test
  void rejected_terminal_replay는_parse와_write전에_실패한다() {
    RecordingRepository repository = new RecordingRepository();
    DetailImageSnapshotGateway rejected =
        new DetailImageSnapshotGateway() {
          @Override
          public SavedDetailImagePage save(
              UUID importRunId, String contentId, int pageNo, DetailSourceResponse response) {
            return saved(importRunId, pageNo, response.payload(), true, SnapshotStatus.REJECTED);
          }

          @Override
          public void markParsed(SavedDetailImagePage page) {
            throw new AssertionError("rejected replay transition 금지");
          }

          @Override
          public void markRejected(SavedDetailImagePage page) {
            throw new AssertionError("terminal replay transition 금지");
          }
        };
    var service =
        new PlaceImageImportService(
            (contentId, pageNo) -> response("network"),
            rejected,
            (format, payload, contentId) -> {
              throw new AssertionError("rejected replay parse 금지");
            },
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importImages(new PlaceImageImportCommand("100", "12", RUN)))
        .isInstanceOf(PlaceImageImportException.class);
    assertThat(repository.command).isNull();
  }

  private static PlaceImagePage page(int pageNo, int pageSize, int total, List<PlaceImage> images) {
    return new PlaceImagePage("100", pageNo, pageSize, total, images.size(), images);
  }

  private static List<PlaceImage> images(int first, int last) {
    return java.util.stream.IntStream.rangeClosed(first, last)
        .mapToObj(
            index -> image("ID-" + index, "https://img.example.test/" + index + ".jpg", index))
        .toList();
  }

  private static PlaceImage image(String id, String url, int order) {
    return new PlaceImage(id, url, null, "이미지 " + order, null, null, null, order);
  }

  private void assertNoWrite(Queue<PlaceImagePage> pages, int failingPage) {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new PlaceImageImportService(
            (contentId, pageNo) -> {
              if (pageNo == failingPage) throw new IllegalStateException("provider failure");
              return response("network");
            },
            new RecordingGateway(null),
            (format, payload, contentId) -> pages.remove(),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importImages(new PlaceImageImportCommand("100", "12", RUN)))
        .isInstanceOf(PlaceImageImportException.class);
    assertThat(repository.command).isNull();
  }

  private static DetailSourceResponse response(String value) {
    return new DetailSourceResponse(value.getBytes(), SnapshotPayloadFormat.JSON);
  }

  private static SavedDetailImagePage saved(
      UUID run, int pageNo, byte[] payload, boolean replayed, SnapshotStatus status) {
    String suffix = String.format("%012d", pageNo);
    return new SavedDetailImagePage(
        new DetailSourceResponse(payload, SnapshotPayloadFormat.JSON),
        pageNo,
        "e".repeat(64),
        NOW.plusSeconds(pageNo),
        new PlaceImageLineage(
            "detailImage2",
            Integer.toHexString(pageNo).repeat(64),
            UUID.fromString("29000000-0000-0000-0002-" + suffix),
            run),
        replayed,
        status);
  }

  private static final class RecordingGateway implements DetailImageSnapshotGateway {
    private final byte[] stored;

    private RecordingGateway(byte[] stored) {
      this.stored = stored == null ? null : stored.clone();
    }

    @Override
    public SavedDetailImagePage save(
        UUID importRunId, String contentId, int pageNo, DetailSourceResponse response) {
      return saved(
          importRunId,
          pageNo,
          stored == null ? response.payload() : stored.clone(),
          false,
          SnapshotStatus.RECEIVED);
    }

    @Override
    public void markParsed(SavedDetailImagePage page) {}

    @Override
    public void markRejected(SavedDetailImagePage page) {}
  }

  private static final class RecordingRepository implements PlaceImageRepository {
    private PlaceImageSyncCommand command;

    @Override
    public PlaceImageSyncResult sync(PlaceImageSyncCommand command) {
      this.command = command;
      return new PlaceImageSyncResult(command.batch().images().size(), 0, 0, 0, 0);
    }
  }
}
