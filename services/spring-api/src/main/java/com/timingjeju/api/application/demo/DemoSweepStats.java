package com.timingjeju.api.application.demo;

public record DemoSweepStats(int expectedTotal, int pageCount) {
  public DemoSweepStats {
    if (expectedTotal < 0 || pageCount < 0) {
      throw new IllegalArgumentException("sweep 통계는 음수일 수 없습니다.");
    }
  }

  public static DemoSweepStats empty() {
    return new DemoSweepStats(0, 0);
  }
}
