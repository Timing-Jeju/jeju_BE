package com.timingjeju.api.domain.places.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.RecommendedStaySource;
import com.timingjeju.api.application.staypolicy.StayPolicyResolutionException;
import com.timingjeju.api.application.staypolicy.StayPolicyResolver;
import com.timingjeju.api.domain.places.dto.response.PlaceDetailResponse;
import com.timingjeju.api.domain.places.exception.PlaceDetailException;
import com.timingjeju.api.domain.places.exception.PlaceDetailUnavailableException;
import com.timingjeju.api.domain.places.model.PlaceDetailImageRow;
import com.timingjeju.api.domain.places.model.PlaceDetailNearbyStopRow;
import com.timingjeju.api.domain.places.model.PlaceDetailSnapshot;
import com.timingjeju.api.domain.places.repository.PlaceDetailRepository;
import com.timingjeju.api.global.text.JsoupPublicPlainTextNormalizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PlaceDetailServiceTest {

  private static final UUID PLACE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final JsoupPublicPlainTextNormalizer PUBLIC_TEXT =
      new JsoupPublicPlainTextNormalizer();

  @Test
  void 상세는_same_snapshot의_operations와_stay_provenance를_닫힌_shape로_반환한다() {
    PlaceDetailRepository repository = mock(PlaceDetailRepository.class);
    StayPolicyResolver stayPolicies = mock(StayPolicyResolver.class);
    when(repository.find(PLACE_ID, Optional.of(USER_ID)))
        .thenReturn(Optional.of(fullSnapshot(true, "오전에 방문", List.of("필수", "동쪽"))));
    when(stayPolicies.resolve(PLACE_ID, "VE"))
        .thenReturn(
            new RecommendedStay(
                70,
                RecommendedStaySource.PLACE_OVERRIDE,
                "stay-2026-summer-v1",
                Instant.parse("2026-08-23T09:00:00Z"),
                Instant.parse("2026-08-23T09:00:05Z")));

    PlaceDetailResponse response =
        new PlaceDetailService(repository, stayPolicies, PUBLIC_TEXT)
            .detail(PLACE_ID, Optional.of(USER_ID));

    assertThat(response.placeId()).isEqualTo(PLACE_ID);
    assertThat(response.thumbnailUrl().toString())
        .isEqualTo("https://images.example.test/first-thumb.jpg");
    assertThat(response.operationsSummary()).isEqualTo("22:00~02:00 · 화요일 휴무 · 주차 가능 · 성인 5,000원");
    assertThat(response.recommendedStayMinutes()).isEqualTo(70);
    assertThat(response.recommendedStaySource()).isEqualTo("place_override");
    assertThat(response.recommendedStayPolicyVersion()).isEqualTo("stay-2026-summer-v1");
    assertThat(response.saved().value()).isTrue();
    assertThat(response.saved().memo()).isEqualTo("오전에 방문");
    assertThat(response.saved().tags()).containsExactly("필수", "동쪽");
    assertThat(response.images())
        .extracting(image -> image.url().toString())
        .containsExactly(
            "https://images.example.test/first.jpg", "https://images.example.test/second.jpg");
    assertThat(response.nearbyStops()).isEmpty();
  }

  @Test
  void 주변정류장은_저장된_provenance와_effective_expiry_stale을_그대로_투영한다() {
    PlaceDetailRepository repository = mock(PlaceDetailRepository.class);
    StayPolicyResolver stayPolicies = mock(StayPolicyResolver.class);
    PlaceDetailSnapshot snapshot =
        fullSnapshot(
            false,
            null,
            List.of(),
            List.of(
                new PlaceDetailNearbyStopRow(
                    UUID.fromString("30000000-0000-0000-0000-000000000001"),
                    "성산일출봉입구",
                    280,
                    null,
                    "spatial_radius",
                    "postgis:tago",
                    Instant.parse("2026-08-03T00:00:00Z"),
                    Instant.parse("2026-08-03T01:00:00Z"),
                    true)));
    when(repository.find(PLACE_ID, Optional.empty())).thenReturn(Optional.of(snapshot));
    when(stayPolicies.resolve(PLACE_ID, "VE")).thenReturn(RecommendedStay.unavailable());

    PlaceDetailResponse response =
        new PlaceDetailService(repository, stayPolicies, PUBLIC_TEXT)
            .detail(PLACE_ID, Optional.empty());

    assertThat(response.nearbyStops())
        .singleElement()
        .satisfies(
            stop -> {
              assertThat(stop.stopId())
                  .isEqualTo(UUID.fromString("30000000-0000-0000-0000-000000000001"));
              assertThat(stop.stopName()).isEqualTo("성산일출봉입구");
              assertThat(stop.distanceMeters()).isEqualTo(280);
              assertThat(stop.walkMinutes()).isNull();
              assertThat(stop.linkMethod()).isEqualTo("spatial_radius");
              assertThat(stop.provider()).isEqualTo("postgis:tago");
              assertThat(stop.observedAt()).isEqualTo(Instant.parse("2026-08-03T00:00:00Z"));
              assertThat(stop.expiresAt()).isEqualTo(Instant.parse("2026-08-03T01:00:00Z"));
              assertThat(stop.stale()).isTrue();
            });
  }

  @Test
  void detail과_image가_없어도_익명은_null과_empty를_생략하지_않는다() {
    PlaceDetailRepository repository = mock(PlaceDetailRepository.class);
    StayPolicyResolver stayPolicies = mock(StayPolicyResolver.class);
    when(repository.find(PLACE_ID, Optional.empty()))
        .thenReturn(Optional.of(emptyOptionalSnapshot()));
    when(stayPolicies.resolve(PLACE_ID, "VE")).thenReturn(RecommendedStay.unavailable());

    PlaceDetailResponse response =
        new PlaceDetailService(repository, stayPolicies, PUBLIC_TEXT)
            .detail(PLACE_ID, Optional.empty());

    assertThat(response.overview()).isNull();
    assertThat(response.thumbnailUrl()).isNull();
    assertThat(response.contact().phone()).isNull();
    assertThat(response.contact().homepageUrl()).isNull();
    assertThat(response.operations().operatingHoursText()).isNull();
    assertThat(response.operationsSummary()).isNull();
    assertThat(response.images()).isEmpty();
    assertThat(response.nearbyStops()).isEmpty();
    assertThat(response.saved().value()).isFalse();
    assertThat(response.saved().memo()).isNull();
    assertThat(response.saved().tags()).isEmpty();
    assertThat(response.recommendedStaySource()).isEqualTo("unavailable");
  }

  @Test
  void 공개가능한_active_place가_없으면_PLACE_NOT_FOUND다() {
    PlaceDetailRepository repository = mock(PlaceDetailRepository.class);
    when(repository.find(PLACE_ID, Optional.empty())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                new PlaceDetailService(repository, mock(StayPolicyResolver.class), PUBLIC_TEXT)
                    .detail(PLACE_ID, Optional.empty()))
        .isInstanceOf(PlaceDetailException.class)
        .extracting("code")
        .isEqualTo("PLACE_NOT_FOUND");
  }

  @Test
  void typed_data_failure만_PLACE_DATA_UNAVAILABLE로_변환한다() {
    PlaceDetailRepository repository = mock(PlaceDetailRepository.class);
    when(repository.find(PLACE_ID, Optional.empty()))
        .thenThrow(new PlaceDetailUnavailableException());

    assertThatThrownBy(
            () ->
                new PlaceDetailService(repository, mock(StayPolicyResolver.class), PUBLIC_TEXT)
                    .detail(PLACE_ID, Optional.empty()))
        .isInstanceOf(PlaceDetailException.class)
        .extracting("code")
        .isEqualTo("PLACE_DATA_UNAVAILABLE");

    PlaceDetailRepository available = mock(PlaceDetailRepository.class);
    StayPolicyResolver stayPolicies = mock(StayPolicyResolver.class);
    when(available.find(PLACE_ID, Optional.empty()))
        .thenReturn(Optional.of(emptyOptionalSnapshot()));
    when(stayPolicies.resolve(PLACE_ID, "VE")).thenThrow(new StayPolicyResolutionException());

    assertThatThrownBy(
            () ->
                new PlaceDetailService(available, stayPolicies, PUBLIC_TEXT)
                    .detail(PLACE_ID, Optional.empty()))
        .isInstanceOf(PlaceDetailException.class)
        .extracting("code")
        .isEqualTo("PLACE_DATA_UNAVAILABLE");
  }

  @Test
  void legacy_detail_text도_public_projection에서_plain_text_1000_code_point로_다시_정규화한다() {
    PlaceDetailRepository repository = mock(PlaceDetailRepository.class);
    StayPolicyResolver stayPolicies = mock(StayPolicyResolver.class);
    String dangerous =
        "<script>secret()</script><style>.x{}</style><b onclick='evil()'>운영&nbsp; 안내</b>"
            + "\u0000  오전\n 9시 "
            + "🍊".repeat(1000);
    PlaceDetailSnapshot legacy =
        new PlaceDetailSnapshot(
            PLACE_ID,
            "126435",
            "성산일출봉",
            "VE",
            "seongsan",
            null,
            null,
            33.458111,
            126.941516,
            null,
            dangerous,
            null,
            dangerous,
            dangerous,
            dangerous,
            dangerous,
            List.of(),
            List.of(),
            false,
            null,
            List.of());
    when(repository.find(PLACE_ID, Optional.empty())).thenReturn(Optional.of(legacy));
    when(stayPolicies.resolve(PLACE_ID, "VE")).thenReturn(RecommendedStay.unavailable());

    PlaceDetailResponse response =
        new PlaceDetailService(repository, stayPolicies, new JsoupPublicPlainTextNormalizer())
            .detail(PLACE_ID, Optional.empty());

    assertThat(response.contact().phone())
        .startsWith("운영 안내 오전 9시")
        .doesNotContain("secret", "onclick", "style");
    assertThat(response.contact().phone().codePointCount(0, response.contact().phone().length()))
        .isEqualTo(1000);
    assertThat(response.operations().operatingHoursText()).isEqualTo(response.contact().phone());
    assertThat(response.operationsSummary()).doesNotContain("secret", "onclick", "style");
    assertThat(
            response.operationsSummary().codePointCount(0, response.operationsSummary().length()))
        .isLessThanOrEqualTo(1000);
  }

  private static PlaceDetailSnapshot fullSnapshot(boolean saved, String memo, List<String> tags) {
    return fullSnapshot(saved, memo, tags, List.of());
  }

  private static PlaceDetailSnapshot fullSnapshot(
      boolean saved, String memo, List<String> tags, List<PlaceDetailNearbyStopRow> nearbyStops) {
    return new PlaceDetailSnapshot(
        PLACE_ID,
        "126435",
        "성산일출봉",
        "VE",
        "seongsan",
        "성산읍",
        "제주특별자치도 서귀포시 성산읍 일출로",
        33.458111,
        126.941516,
        "제주 동쪽의 대표 오름 관광지입니다.",
        "064-000-0001",
        "https://www.example.test/place",
        "22:00~02:00",
        "화요일 휴무",
        "주차 가능",
        "성인 5,000원",
        List.of(
            image(
                "32000000-0000-0000-0000-000000000001",
                "https://images.example.test/first.jpg",
                "https://images.example.test/first-thumb.jpg"),
            image(
                "32000000-0000-0000-0000-000000000002",
                "https://images.example.test/second.jpg",
                null)),
        nearbyStops,
        saved,
        memo,
        tags);
  }

  private static PlaceDetailSnapshot emptyOptionalSnapshot() {
    return new PlaceDetailSnapshot(
        PLACE_ID,
        "126435",
        "성산일출봉",
        "VE",
        "seongsan",
        null,
        null,
        33.458111,
        126.941516,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        false,
        null,
        List.of());
  }

  private static PlaceDetailImageRow image(String id, String url, String thumbnailUrl) {
    return new PlaceDetailImageRow(
        UUID.fromString(id),
        url,
        thumbnailUrl,
        "TOUR_API",
        Instant.parse("2026-08-03T00:00:00Z"),
        Instant.parse("2026-08-04T00:00:00Z"),
        false);
  }
}
