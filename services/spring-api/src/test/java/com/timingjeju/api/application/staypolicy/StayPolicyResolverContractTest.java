package com.timingjeju.api.application.staypolicy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class StayPolicyResolverContractTest {

  private static final UUID PLACE = UUID.fromString("65000000-0000-0000-0000-000000000001");

  @Test
  void 목록과_상세가_공유할_resolver는_override_category_unavailable_우선순위와_provenance를_반환한다() {
    Instant effectiveAt = Instant.parse("2026-08-23T09:00:00Z");
    Instant updatedAt = effectiveAt.plusSeconds(5);
    StayPolicyLookup lookup =
        (placeId, category) ->
            switch (category) {
              case "OVERRIDE" ->
                  Optional.of(
                      new RecommendedStay(
                          120, RecommendedStaySource.PLACE_OVERRIDE, "v2", effectiveAt, updatedAt));
              case "CATEGORY" ->
                  Optional.of(
                      new RecommendedStay(
                          90,
                          RecommendedStaySource.CATEGORY_DEFAULT,
                          "v2",
                          effectiveAt,
                          updatedAt));
              default -> Optional.empty();
            };
    StayPolicyResolver resolver = new DefaultStayPolicyResolver(lookup);

    assertThat(resolver.resolve(PLACE, "OVERRIDE").source())
        .isEqualTo(RecommendedStaySource.PLACE_OVERRIDE);
    assertThat(resolver.resolve(PLACE, "CATEGORY").source())
        .isEqualTo(RecommendedStaySource.CATEGORY_DEFAULT);
    assertThat(resolver.resolve(PLACE, "NONE")).isEqualTo(RecommendedStay.unavailable());
  }
}
