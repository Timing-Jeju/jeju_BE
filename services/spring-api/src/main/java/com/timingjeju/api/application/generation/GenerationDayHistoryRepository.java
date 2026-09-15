package com.timingjeju.api.application.generation;

import java.util.List;

/** 해시된 base version이 참조하는 불변 Day 이력을 복원한다. */
public interface GenerationDayHistoryRepository {
  List<GenerationSelectedDay> findPrevious(GenerationTripInput input);
}
