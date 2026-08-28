package com.timingjeju.api.domain.savedplaces.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SavedPlacesProblemDefinitionsTest {
  @Test
  void savedplaces가_공통_registry문구와_충돌하지_않고_canonical_문제_문구를_소유한다() {
    var definitions =
        new SavedPlacesProblemDefinitions()
            .definitions().stream()
                .collect(Collectors.toMap(definition -> definition.code(), Function.identity()));

    assertThat(definitions.get("INVALID_QUERY_PARAMETER"))
        .extracting("title", "detail")
        .containsExactly("조회 조건이 올바르지 않습니다", "관심 장소 조회 조건을 확인해 주세요.");
    assertThat(definitions.get("INVALID_CURSOR"))
        .extracting("title", "detail")
        .containsExactly("커서가 올바르지 않습니다", "처음부터 다시 조회해 주세요.");
    assertThat(definitions.get("CURSOR_CONTEXT_MISMATCH"))
        .extracting("title", "detail")
        .containsExactly("커서의 조회 조건이 현재 요청과 다릅니다", "변경한 조건으로 처음부터 다시 조회해 주세요.");
    assertThat(definitions.get("PLACE_NOT_FOUND"))
        .extracting("title", "detail")
        .containsExactly("장소를 찾을 수 없습니다", "저장하려는 장소 정보가 없습니다.");
  }
}
