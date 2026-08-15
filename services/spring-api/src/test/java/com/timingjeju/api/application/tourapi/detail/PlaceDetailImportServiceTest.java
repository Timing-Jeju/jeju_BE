package com.timingjeju.api.application.tourapi.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PlaceDetailImportServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-16T06:00:00Z");
  private static final DetailLineage COMMON =
      new DetailLineage("detailCommon2", "2".repeat(64), UUID.randomUUID(), UUID.randomUUID());
  private static final DetailLineage INTRO =
      new DetailLineage("detailIntro2", "3".repeat(64), UUID.randomUUID(), UUID.randomUUID());

  @Test
  void common_intro를_분리호출하고_같은_content_type의_상세를_한번_저장한다() {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new PlaceDetailImportService(
            contentId -> response("common"),
            (contentId, type) -> response("intro"),
            (format, payload) -> common("100", "12"),
            (format, payload) -> intro("100", "12"),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    var result = service.importDetail(new PlaceDetailImportCommand("100", "12", COMMON, INTRO));

    assertThat(result).isEqualTo(PlaceDetailUpsertResult.insertedResult());
    assertThat(repository.command.contentId()).isEqualTo("100");
    assertThat(repository.command.fetchedAt()).isEqualTo(NOW);
    assertThat(repository.command.commonLineage()).isEqualTo(COMMON);
    assertThat(repository.command.introLineage()).isEqualTo(INTRO);
  }

  @Test
  void common_intro_식별자나_요청_content_type이_다르면_repository_write전에_거부한다() {
    RecordingRepository repository = new RecordingRepository();
    var service =
        new PlaceDetailImportService(
            contentId -> response("common"),
            (contentId, type) -> response("intro"),
            (format, payload) -> common("100", "12"),
            (format, payload) -> intro("100", "39"),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () -> service.importDetail(new PlaceDetailImportCommand("100", "12", COMMON, INTRO)))
        .isInstanceOf(PlaceDetailImportException.class);
    assertThat(repository.command).isNull();
  }

  private static DetailSourceResponse response(String text) {
    return new DetailSourceResponse(
        text.getBytes(java.nio.charset.StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
  }

  private static PlaceDetailCommon common(String id, String type) {
    return new PlaceDetailCommon(id, type, "064", null, "<p>개요</p>", "개요", NOW);
  }

  private static PlaceDetailIntro intro(String id, String type) {
    return new PlaceDetailIntro(
        id, type, null, null, null, null, null, null, null, null, null, Map.of());
  }

  private static final class RecordingRepository implements PlaceDetailRepository {
    private PlaceDetailUpsertCommand command;

    @Override
    public PlaceDetailUpsertResult upsert(PlaceDetailUpsertCommand command) {
      this.command = command;
      return PlaceDetailUpsertResult.insertedResult();
    }
  }
}
