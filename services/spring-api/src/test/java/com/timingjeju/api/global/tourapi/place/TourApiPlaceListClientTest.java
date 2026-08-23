package com.timingjeju.api.global.tourapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TourApiPlaceListClientTest {

  @Test
  void page외_request조건은_areaBasedList2와_제주_scope로_고정한다() {
    AtomicReference<PlaceListHttpRequest> captured = new AtomicReference<>();
    TourApiPlaceListClient client =
        new TourApiPlaceListClient(
            request -> {
              captured.set(request);
              return "{}".getBytes(StandardCharsets.UTF_8);
            });

    var response = client.fetch(2);

    assertThat(response.format()).isEqualTo(SnapshotPayloadFormat.JSON);
    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TOUR_AREA_BASED_LIST);
    assertThat(captured.get().relativePath()).isEqualTo("areaBasedList2");
    assertThat(captured.get().queryParameters())
        .containsEntry("pageNo", "2")
        .containsEntry("numOfRows", "100")
        .containsEntry("lDongRegnCd", "50")
        .containsEntry("MobileOS", "ETC")
        .containsEntry("MobileApp", "TimingJeju")
        .containsEntry("_type", "json")
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization", "areaCode");
  }
}
