package com.timingjeju.api.global.tourapi.detail;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TourApiPlaceDetailClientTest {

  @Test
  void common과_intro는_서로_다른_operation과_최소_query를_사용한다() {
    AtomicReference<PlaceDetailHttpRequest> commonRequest = new AtomicReference<>();
    AtomicReference<PlaceDetailHttpRequest> introRequest = new AtomicReference<>();
    var common =
        new TourApiDetailCommonClient(
            request -> {
              commonRequest.set(request);
              return "{}".getBytes(StandardCharsets.UTF_8);
            });
    var intro =
        new TourApiDetailIntroClient(
            request -> {
              introRequest.set(request);
              return "{}".getBytes(StandardCharsets.UTF_8);
            });

    common.fetch("100");
    intro.fetch("100", "12");

    assertThat(commonRequest.get().operation()).isEqualTo(ExternalApiOperation.TOUR_DETAIL_COMMON);
    assertThat(commonRequest.get().relativePath()).isEqualTo("detailCommon2");
    assertThat(commonRequest.get().queryParameters())
        .containsEntry("contentId", "100")
        .containsEntry("overviewYN", "Y")
        .doesNotContainKeys("contentTypeId", "serviceKey", "apiKey", "Authorization");
    assertThat(introRequest.get().operation()).isEqualTo(ExternalApiOperation.TOUR_DETAIL_INTRO);
    assertThat(introRequest.get().relativePath()).isEqualTo("detailIntro2");
    assertThat(introRequest.get().queryParameters())
        .containsEntry("contentId", "100")
        .containsEntry("contentTypeId", "12")
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization");
  }
}
