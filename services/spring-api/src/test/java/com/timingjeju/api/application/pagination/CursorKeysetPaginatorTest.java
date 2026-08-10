package com.timingjeju.api.application.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CursorKeysetPaginatorTest {

  private static final CursorCodec CODEC =
      CursorCodec.hmacSha256("test-only-cursor-signing-key-32-bytes");
  private static final String FILTER_FINGERPRINT =
      CursorFilterFingerprint.sha256(Map.of("query", "성산"));
  private static final CursorContext SCORE_ASC_CONTEXT =
      new CursorContext("/api/v1/places", CursorSort.asc("score", "id"), FILTER_FINGERPRINT);
  private static final CursorContext SCORE_DESC_CONTEXT =
      new CursorContext("/api/v1/places", CursorSort.desc("score", "id"), FILTER_FINGERPRINT);
  private static final Comparator<String> NUMERIC_SCORE_ORDER =
      Comparator.comparingInt(Integer::parseInt);
  private static final Comparator<String> STRING_ORDER = Comparator.naturalOrder();
  private static final Comparator<String> ISO_INSTANT_ORDER = Comparator.comparing(Instant::parse);

  @Test
  void 숫자_sortValue는_ASC에서_2_9_10_순서로_정렬한다() {
    List<Row> rows = List.of(new Row("p-10", "10"), new Row("p-9", "9"), new Row("p-2", "2"));

    CursorPage<Row> page =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(3, null, SCORE_ASC_CONTEXT, CODEC),
            Row::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(page.items()).extracting(Row::id).containsExactly("p-2", "p-9", "p-10");
  }

  @Test
  void 숫자_sortValue는_DESC에서_10_9_2_순서로_정렬한다() {
    List<Row> rows = List.of(new Row("p-10", "10"), new Row("p-9", "9"), new Row("p-2", "2"));

    CursorPage<Row> page =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(3, null, SCORE_DESC_CONTEXT, CODEC),
            Row::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(page.items()).extracting(Row::id).containsExactly("p-10", "p-9", "p-2");
  }

  @Test
  void 동일한_숫자_sortValue는_유일한_tieBreaker로_안정적인_순서를_유지한다() {
    List<Row> rows =
        List.of(new Row("p-003", "10"), new Row("p-001", "10"), new Row("p-002", "10"));

    CursorPage<Row> page =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(3, null, SCORE_DESC_CONTEXT, CODEC),
            Row::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(page.items()).extracting(Row::id).containsExactly("p-003", "p-002", "p-001");
  }

  @Test
  void 숫자_첫_페이지의_nextCursor는_다음_페이지_경계를_보존한다() {
    List<Row> rows = List.of(new Row("p-10", "10"), new Row("p-9", "9"), new Row("p-2", "2"));

    CursorPage<Row> first =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(2, null, SCORE_DESC_CONTEXT, CODEC),
            Row::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);
    CursorPage<Row> second =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(2, first.page().nextCursor(), SCORE_DESC_CONTEXT, CODEC),
            Row::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(first.items()).extracting(Row::id).containsExactly("p-10", "p-9");
    assertThat(second.items()).extracting(Row::id).containsExactly("p-2");
  }

  @Test
  void 문자열과_ISO_시간_sortValue는_각_의미에_맞는_순서를_유지한다() {
    CursorContext nameContext =
        new CursorContext("/api/v1/places", CursorSort.asc("name", "id"), FILTER_FINGERPRINT);
    CursorContext timeContext =
        new CursorContext("/api/v1/places", CursorSort.asc("createdAt", "id"), FILTER_FINGERPRINT);

    CursorPage<Row> names =
        CursorKeysetPaginator.page(
            List.of(new Row("p-b", "beta"), new Row("p-a", "alpha")),
            CursorPageRequest.of(2, null, nameContext, CODEC),
            Row::position,
            STRING_ORDER,
            STRING_ORDER);
    CursorPage<Row> times =
        CursorKeysetPaginator.page(
            List.of(
                new Row("p-late", "2026-08-05T12:00:00Z"),
                new Row("p-early", "2026-08-05T09:00:00Z")),
            CursorPageRequest.of(2, null, timeContext, CODEC),
            Row::position,
            ISO_INSTANT_ORDER,
            STRING_ORDER);

    assertThat(names.items()).extracting(Row::id).containsExactly("p-a", "p-b");
    assertThat(times.items()).extracting(Row::id).containsExactly("p-early", "p-late");
  }

  @Test
  void 숫자_첫_페이지_후_중간값이_추가되어도_다음_페이지에_중복이_없다() {
    List<Row> rows =
        new ArrayList<>(List.of(new Row("p-30", "30"), new Row("p-9", "9"), new Row("p-2", "2")));
    CursorPage<Row> first =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(2, null, SCORE_DESC_CONTEXT, CODEC),
            Row::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    rows.add(new Row("p-10", "10"));
    CursorPage<Row> second =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(2, first.page().nextCursor(), SCORE_DESC_CONTEXT, CODEC),
            Row::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(first.items()).extracting(Row::id).containsExactly("p-30", "p-9");
    assertThat(second.items()).extracting(Row::id).containsExactly("p-2");
    assertThat(second.items()).extracting(Row::id).doesNotContain("p-10", "p-9");
  }

  private record Row(String id, String sortValue) {
    private CursorPosition position() {
      return new CursorPosition(sortValue, id);
    }
  }
}
