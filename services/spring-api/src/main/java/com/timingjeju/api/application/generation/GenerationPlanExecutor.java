package com.timingjeju.api.application.generation;

import java.time.Instant;
import java.util.UUID;

/** claim된 run의 저장 입력을 읽어 추천을 실행한다. 성공 저장과 lease/fence는 worker가 소유한다. */
public interface GenerationPlanExecutor {
  GenerationCandidateProjection execute(UUID runId, Instant deadline);
}
