package com.timingjeju.api.global.tourapi.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCursor;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TourApiIncrementalSyncClientTest {

  @Test
  void cursor와_제주_scope_fixed_pagination으로_areaBasedSyncList2를_호출한다() {
    AtomicReference<IncrementalSyncHttpRequest> captured = new AtomicReference<>();
    TourApiIncrementalSyncClient client =
        new TourApiIncrementalSyncClient(
            request -> {
              captured.set(request);
              return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            });

    client.fetch(new IncrementalSyncCursor(Instant.parse("2026-08-16T01:02:03Z")), 3);

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TOUR_AREA_SYNC);
    assertThat(captured.get().relativePath()).isEqualTo("/areaBasedSyncList2");
    assertThat(captured.get().format()).isEqualTo(ExternalApiResponseFormat.JSON);
    assertThat(captured.get().queryParameters())
        .containsEntry("numOfRows", "100")
        .containsEntry("pageNo", "3")
        .containsEntry("MobileOS", "ETC")
        .containsEntry("MobileApp", "TimingJeju")
        .containsEntry("_type", "json")
        .containsEntry("lDongRegnCd", "50")
        .containsEntry("modifiedtime", "20260816100203")
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization");
  }

  @Test
  void page는_1부터_시작한다() {
    TourApiIncrementalSyncClient client = new TourApiIncrementalSyncClient(request -> new byte[0]);

    assertThatThrownBy(
            () -> client.fetch(new IncrementalSyncCursor(Instant.parse("2026-08-16T00:00:00Z")), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
