package com.timingjeju.api.application.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
            (id, type) -> response(),
            (format, payload, id, type) -> batch("100", "12"),
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
            (id, type) -> response(),
            (format, payload, id, type) -> batch("other", "12"),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.importItems(new DetailItemImportCommand("100", "12", LINEAGE)))
        .isInstanceOf(DetailItemImportException.class);
    assertThat(repository.command).isNull();
  }

  private static DetailItemBatch batch(String id, String type) {
    return new DetailItemBatch(
        id,
        type,
        List.of(
            new DetailItem(
                "info",
                "1",
                "안내",
                1,
                new DetailItemAttributes(
                    "tour-api.detailInfo2.info", 1, Map.of("infotext", "본문")))));
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
