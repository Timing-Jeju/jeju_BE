package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.asyncrun.RunLease;
import java.time.Duration;
import java.util.List;

/** 성공 전이는 후보 세 개 또는 생성 불가 결과의 원자 저장 경계가 소유한다. */
public interface GenerationRunLeases {
  List<RunLease> claimAvailable(String workerId, Duration leaseDuration, int limit);

  boolean heartbeat(RunLease lease, Duration leaseDuration);

  boolean fail(RunLease lease, String stableErrorCode);

  boolean retry(RunLease lease, Duration delay, String stableErrorCode);
}
