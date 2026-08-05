package com.timingjeju.api.application.pagination;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class CursorKeysetPaginator {

  private CursorKeysetPaginator() {}

  /** 직렬화 문자열의 타입을 추측하지 않는다. 호출자는 sort 값과 tie-breaker의 오름차순 비교 의미를 각각 명시해야 한다. */
  public static <T> CursorPage<T> page(
      List<T> rows,
      CursorPageRequest request,
      Function<T, CursorPosition> positionExtractor,
      Comparator<String> ascendingSortValueComparator,
      Comparator<String> ascendingTieBreakerComparator) {
    Comparator<CursorPosition> ascendingPositionComparator =
        positionComparator(ascendingSortValueComparator, ascendingTieBreakerComparator);
    Comparator<T> rowComparator =
        rowComparator(request.context().sort(), positionExtractor, ascendingPositionComparator);
    List<T> sortedRows = rows.stream().sorted(rowComparator).toList();
    List<T> window =
        sortedRows.stream()
            .filter(
                row ->
                    isAfter(
                        positionExtractor.apply(row),
                        request.after(),
                        request.context().sort(),
                        ascendingPositionComparator))
            .limit((long) request.size() + 1)
            .toList();
    boolean hasNext = window.size() > request.size();
    List<T> items = hasNext ? window.subList(0, request.size()) : window;
    String nextCursor =
        hasNext
            ? request.codec().encode(request.context(), positionExtractor.apply(items.getLast()))
            : null;
    return new CursorPage<>(items, new CursorPageInfo(request.size(), hasNext, nextCursor));
  }

  private static Comparator<CursorPosition> positionComparator(
      Comparator<String> ascendingSortValueComparator,
      Comparator<String> ascendingTieBreakerComparator) {
    Objects.requireNonNull(ascendingSortValueComparator, "ascendingSortValueComparator");
    Objects.requireNonNull(ascendingTieBreakerComparator, "ascendingTieBreakerComparator");
    return Comparator.comparing(CursorPosition::sortValue, ascendingSortValueComparator)
        .thenComparing(CursorPosition::tieBreaker, ascendingTieBreakerComparator);
  }

  private static <T> Comparator<T> rowComparator(
      CursorSort sort,
      Function<T, CursorPosition> positionExtractor,
      Comparator<CursorPosition> ascendingPositionComparator) {
    Comparator<T> comparator = Comparator.comparing(positionExtractor, ascendingPositionComparator);
    if (sort.direction() == CursorDirection.DESC) {
      comparator = comparator.reversed();
    }
    return comparator;
  }

  private static boolean isAfter(
      CursorPosition position,
      CursorPosition cursor,
      CursorSort sort,
      Comparator<CursorPosition> ascendingPositionComparator) {
    if (cursor == null) {
      return true;
    }
    int comparison = ascendingPositionComparator.compare(position, cursor);
    return sort.direction() == CursorDirection.ASC ? comparison > 0 : comparison < 0;
  }
}
