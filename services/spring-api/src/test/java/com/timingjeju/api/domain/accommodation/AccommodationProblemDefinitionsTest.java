package com.timingjeju.api.domain.accommodation;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.accommodation.exception.AccommodationProblemDefinitions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccommodationProblemDefinitionsTest {
  @Test
  void 일정_domain이_공유하는_숙소_not_found는_registry에_중복_등록하지_않는다() {
    AccommodationProblemDefinitions definitions = new AccommodationProblemDefinitions();

    assertThat(definitions.find("ACCOMMODATION_NOT_FOUND").code())
        .isEqualTo("ACCOMMODATION_NOT_FOUND");
    assertThat(definitions.definitions())
        .extracting(definition -> definition.code())
        .doesNotContain("ACCOMMODATION_NOT_FOUND")
        .contains(
            "ACCOMMODATION_CONCURRENT_CONFLICT",
            "ACCOMMODATION_DATE_GAP_OR_OVERLAP",
            "ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE");
  }
}
