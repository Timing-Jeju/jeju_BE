package com.timingjeju.api.domain.places.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.RecommendedStaySource;
import com.timingjeju.api.application.staypolicy.StayPolicyResolver;
import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import com.timingjeju.api.domain.places.dto.response.PlacesListResponse;
import com.timingjeju.api.domain.places.repository.PlaceSearchRepository;
import com.timingjeju.api.domain.places.repository.PlaceSearchRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PlaceListServiceTest {

  private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("10000000-0000-0000-0000-000000000002");

  @Test
  void size_plus_one_조회와_단일_batch_체류정책으로_cursor_page를_만든다() {
    PlaceSearchRepository repository = mock(PlaceSearchRepository.class);
    StayPolicyResolver resolver = mock(StayPolicyResolver.class);
    when(repository.search(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(row(FIRST, "성산일출봉", false), row(SECOND, "성산일출봉", true)));
    when(resolver.resolveAll(anyList()))
        .thenReturn(
            Map.of(
                FIRST,
                new RecommendedStay(
                    90,
                    RecommendedStaySource.PLACE_OVERRIDE,
                    "2026.08",
                    Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2026-08-02T00:00:00Z"))));
    PlaceListService service =
        new PlaceListService(
            repository, resolver, CursorCodec.hmacSha256("test-only-place-cursor-key-32-bytes"));

    PlacesListResponse response =
        service.list(
            PlacesListQuery.of(" 성산 ", null, null, null, null, null, null, 1, false),
            Optional.empty());

    assertThat(response.items()).hasSize(1);
    assertThat(response.items().getFirst().recommendedStayMinutes()).isEqualTo(90);
    assertThat(response.items().getFirst().recommendedStaySource()).isEqualTo("place_override");
    assertThat(response.page().hasNext()).isTrue();
    assertThat(response.page().nextCursor()).isNotBlank();
    verify(resolver).resolveAll(anyList());
  }

  @Test
  void savedOnly는_current_user가_없으면_401_domain_code로_거부한다() {
    PlaceListService service =
        new PlaceListService(
            mock(PlaceSearchRepository.class),
            mock(StayPolicyResolver.class),
            CursorCodec.hmacSha256("test-only-place-cursor-key-32-bytes"));

    assertThatThrownBy(
            () ->
                service.list(
                    PlacesListQuery.of(null, null, null, null, null, null, null, 20, true),
                    Optional.empty()))
        .isInstanceOf(PlaceListException.class)
        .extracting("code")
        .isEqualTo("AUTHENTICATION_REQUIRED");
  }

  @Test
  void cursor는_HMAC_위변조와_필터_fingerprint_변경을_서로_다른_code로_거부한다() {
    PlaceSearchRepository repository = mock(PlaceSearchRepository.class);
    StayPolicyResolver resolver = mock(StayPolicyResolver.class);
    when(repository.search(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(row(FIRST, "성산일출봉", false), row(SECOND, "성산일출봉", false)));
    when(resolver.resolveAll(anyList())).thenReturn(Map.of());
    PlaceListService service =
        new PlaceListService(
            repository, resolver, CursorCodec.hmacSha256("test-only-place-cursor-key-32-bytes"));
    PlacesListQuery firstQuery =
        PlacesListQuery.of("성산", null, null, null, null, null, null, 1, false);
    String cursor = service.list(firstQuery, Optional.empty()).page().nextCursor();

    assertThatThrownBy(
            () ->
                service.list(
                    PlacesListQuery.of("다른검색", null, null, null, null, null, cursor, 1, false),
                    Optional.empty()))
        .isInstanceOf(PlaceListException.class)
        .extracting("code")
        .isEqualTo("CURSOR_CONTEXT_MISMATCH");

    String tampered = cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "B" : "A");
    assertThatThrownBy(
            () ->
                service.list(
                    PlacesListQuery.of("성산", null, null, null, null, null, tampered, 1, false),
                    Optional.empty()))
        .isInstanceOf(PlaceListException.class)
        .extracting("code")
        .isEqualTo("INVALID_CURSOR");
  }

  private static PlaceSearchRow row(UUID id, String name, boolean stale) {
    return new PlaceSearchRow(
        id,
        id.toString(),
        name,
        name,
        "tourist_attraction",
        "seongsan",
        "성산읍",
        "제주특별자치도",
        33.458,
        126.94,
        "https://images.example.test/thumb.jpg",
        "09:00~18:00",
        null,
        "TOUR_API",
        Instant.parse("2026-08-01T00:00:00Z"),
        Instant.parse("2026-08-02T00:00:00Z"),
        stale,
        false,
        null,
        List.of());
  }
}
