package com.timingjeju.api.application.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CursorPaginationContractTest {

  private static final CursorCodec CODEC =
      CursorCodec.hmacSha256("test-only-cursor-signing-key-32-bytes");
  private static final CursorContext CONTEXT =
      new CursorContext(
          "/api/v1/places",
          CursorSort.desc("score", "id"),
          CursorFilterFingerprint.sha256(Map.of("query", "성산")));
  private static final Comparator<String> NUMERIC_SCORE_ORDER =
      Comparator.comparingInt(Integer::parseInt);
  private static final Comparator<String> STRING_ORDER = Comparator.naturalOrder();

  @Test
  void size는_기본_20이고_1부터_50까지만_허용한다() {
    assertThat(CursorPageRequest.of(null, null, CONTEXT, CODEC).size()).isEqualTo(20);
    assertThat(CursorPageRequest.of(1, null, CONTEXT, CODEC).size()).isEqualTo(1);
    assertThat(CursorPageRequest.of(50, null, CONTEXT, CODEC).size()).isEqualTo(50);

    assertThatThrownBy(() -> CursorPageRequest.of(0, null, CONTEXT, CODEC))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CursorPageRequest.of(51, null, CONTEXT, CODEC))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void null_cursor만_미제공이고_빈문자열과_공백은_CURSOR_INVALID로_거부한다() {
    assertThat(CursorPageRequest.of(20, null, CONTEXT, CODEC).after()).isNull();

    assertThatThrownBy(() -> CursorPageRequest.of(20, "", CONTEXT, CODEC))
        .isInstanceOf(CursorInvalidException.class);
    assertThatThrownBy(() -> CursorPageRequest.of(20, "   ", CONTEXT, CODEC))
        .isInstanceOf(CursorInvalidException.class);
  }

  @Test
  void 데이터_21개를_size20으로_조회하면_20개와_nextCursor를_반환한다() {
    List<PlaceRow> rows =
        IntStream.rangeClosed(1, 21)
            .mapToObj(index -> new PlaceRow("p-%03d".formatted(index), 100 - index))
            .toList();

    CursorPage<PlaceRow> page =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(20, null, CONTEXT, CODEC),
            PlaceRow::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(page.items()).hasSize(20);
    assertThat(page.page().size()).isEqualTo(20);
    assertThat(page.page().hasNext()).isTrue();
    assertThat(page.page().nextCursor()).isNotBlank();
    assertThat(page.items())
        .extracting(PlaceRow::id)
        .containsExactly(
            "p-001", "p-002", "p-003", "p-004", "p-005", "p-006", "p-007", "p-008", "p-009",
            "p-010", "p-011", "p-012", "p-013", "p-014", "p-015", "p-016", "p-017", "p-018",
            "p-019", "p-020");
  }

  @Test
  void 동일_정렬값은_유일한_tieBreaker로_안정적인_순서를_유지한다() {
    List<PlaceRow> rows =
        List.of(new PlaceRow("p-003", 10), new PlaceRow("p-001", 10), new PlaceRow("p-002", 10));

    CursorPage<PlaceRow> page =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(3, null, CONTEXT, CODEC),
            PlaceRow::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(page.items()).extracting(PlaceRow::id).containsExactly("p-003", "p-002", "p-001");
  }

  @Test
  void 첫_페이지_후_중간_데이터가_추가되어도_keyset_기준으로_중복을_반환하지_않는다() {
    List<PlaceRow> rows = new ArrayList<>();
    IntStream.rangeClosed(1, 21)
        .forEach(index -> rows.add(new PlaceRow("p-%03d".formatted(index), 100 - index)));
    CursorPage<PlaceRow> first =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(20, null, CONTEXT, CODEC),
            PlaceRow::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    rows.add(new PlaceRow("p-new", 95));
    CursorPage<PlaceRow> second =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(20, first.page().nextCursor(), CONTEXT, CODEC),
            PlaceRow::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(second.items()).extracting(PlaceRow::id).containsExactly("p-021");
    assertThat(second.items()).extracting(PlaceRow::id).doesNotContain("p-new", "p-020");
  }

  @Test
  void 마지막_페이지는_nextCursor_null과_hasNext_false를_반환한다() {
    List<PlaceRow> rows = List.of(new PlaceRow("p-001", 10), new PlaceRow("p-002", 9));

    CursorPage<PlaceRow> page =
        CursorKeysetPaginator.page(
            rows,
            CursorPageRequest.of(20, null, CONTEXT, CODEC),
            PlaceRow::position,
            NUMERIC_SCORE_ORDER,
            STRING_ORDER);

    assertThat(page.items()).hasSize(2);
    assertThat(page.page().hasNext()).isFalse();
    assertThat(page.page().nextCursor()).isNull();
  }

  record PlaceRow(String id, int score) {
    CursorPosition position() {
      return new CursorPosition(String.valueOf(score), id);
    }
  }
}
