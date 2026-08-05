package com.timingjeju.api.application.pagination;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public final class CursorKeysetPaginator {

  private CursorKeysetPaginator() {}

  public static <T> CursorPage<T> page(
      List<T> rows, CursorPageRequest request, Function<T, CursorPosition> positionExtractor) {
    List<T> sortedRows =
        rows.stream().sorted(comparator(request.context().sort(), positionExtractor)).toList();
    List<T> window =
        sortedRows.stream()
            .filter(
                row ->
                    isAfter(
                        positionExtractor.apply(row), request.after(), request.context().sort()))
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

  private static <T> Comparator<T> comparator(
      CursorSort sort, Function<T, CursorPosition> positionExtractor) {
    Comparator<T> comparator =
        Comparator.comparing((T row) -> positionExtractor.apply(row).sortValue())
            .thenComparing(row -> positionExtractor.apply(row).tieBreaker());
    if (sort.direction() == CursorDirection.DESC) {
      comparator = comparator.reversed();
    }
    return comparator;
  }

  private static boolean isAfter(CursorPosition position, CursorPosition cursor, CursorSort sort) {
    if (cursor == null) {
      return true;
    }
    int sortComparison = position.sortValue().compareTo(cursor.sortValue());
    if (sortComparison == 0) {
      sortComparison = position.tieBreaker().compareTo(cursor.tieBreaker());
    }
    return sort.direction() == CursorDirection.ASC ? sortComparison > 0 : sortComparison < 0;
  }
}
