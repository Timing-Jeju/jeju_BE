package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.asyncrun.RunLease;
import java.time.Instant;

/** 후보 전체와 run 성공을 한 트랜잭션으로 저장한다. 부분 저장·별도 성공 전이는 금지한다. */
public interface GenerationCompletionStore {
  /**
   * 현재 lease/fence·기준 버전·DB 시각의 deadline을 쓰기 전후 검증한다. 권한 상실은 무수정 false, 저장 실패는 전체 rollback 후 예외다.
   * 정상 반환 전에 commit되어야 하며 외부 호출은 수행하지 않는다.
   */
  boolean complete(RunLease lease, GenerationCandidateProjection result, Instant deadline);
}
