package com.timingjeju.api.application.importing;

public record ImportRunCounts(
    int rowCount,
    int fetchedCount,
    int insertedCount,
    int updatedCount,
    int skippedCount,
    int rejectedCount,
    int deletedCount,
    int staledCount) {

  public ImportRunCounts {
    if (rowCount < 0
        || fetchedCount < 0
        || insertedCount < 0
        || updatedCount < 0
        || skippedCount < 0
        || rejectedCount < 0
        || deletedCount < 0
        || staledCount < 0) {
      throw new IllegalArgumentException("import count는 음수일 수 없습니다.");
    }
  }

  public static ImportRunCounts zero() {
    return new ImportRunCounts(0, 0, 0, 0, 0, 0, 0, 0);
  }

  public ImportRunCounts plus(ImportRunCounts other) {
    return new ImportRunCounts(
        Math.addExact(rowCount, other.rowCount),
        Math.addExact(fetchedCount, other.fetchedCount),
        Math.addExact(insertedCount, other.insertedCount),
        Math.addExact(updatedCount, other.updatedCount),
        Math.addExact(skippedCount, other.skippedCount),
        Math.addExact(rejectedCount, other.rejectedCount),
        Math.addExact(deletedCount, other.deletedCount),
        Math.addExact(staledCount, other.staledCount));
  }
}
