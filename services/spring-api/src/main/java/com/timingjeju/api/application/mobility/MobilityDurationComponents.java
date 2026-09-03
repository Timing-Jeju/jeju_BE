package com.timingjeju.api.application.mobility;

public record MobilityDurationComponents(
    int accessWalkMinutes,
    int waitMinutes,
    int rideMinutes,
    int transferWalkMinutes,
    int egressWalkMinutes) {
  private static final int MAX_COMPONENT_MINUTES = 10_080;

  public MobilityDurationComponents {
    requireMinutes(accessWalkMinutes);
    requireMinutes(waitMinutes);
    requireMinutes(rideMinutes);
    requireMinutes(transferWalkMinutes);
    requireMinutes(egressWalkMinutes);
    if (totalAsLong(
            accessWalkMinutes, waitMinutes, rideMinutes, transferWalkMinutes, egressWalkMinutes)
        > MAX_COMPONENT_MINUTES) {
      throw new IllegalArgumentException("duration 구성요소 합계가 제한을 초과했습니다.");
    }
  }

  public int totalMinutes() {
    return Math.toIntExact(
        totalAsLong(
            accessWalkMinutes, waitMinutes, rideMinutes, transferWalkMinutes, egressWalkMinutes));
  }

  private static void requireMinutes(int value) {
    if (value < 0 || value > MAX_COMPONENT_MINUTES) {
      throw new IllegalArgumentException("duration 구성요소가 허용 범위를 벗어났습니다.");
    }
  }

  private static long totalAsLong(int... values) {
    long total = 0;
    for (int value : values) total += value;
    return total;
  }
}
