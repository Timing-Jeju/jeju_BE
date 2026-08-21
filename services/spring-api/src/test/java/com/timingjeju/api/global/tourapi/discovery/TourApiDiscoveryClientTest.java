package com.timingjeju.api.global.tourapi.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.tourapi.discovery.DiscoveryImportCommand;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TourApiDiscoveryClientTest {

  @Test
  void 세_operation은_각_endpoint와_공개가능한_query만_전송한다() {
    AtomicReference<DiscoveryHttpRequest> captured = new AtomicReference<>();
    TourApiDiscoveryClient client =
        new TourApiDiscoveryClient(
            request -> {
              captured.set(request);
              return "{}".getBytes();
            });

    client.fetch(DiscoveryImportCommand.location(126.5, 33.5, 1000, 2, "location"), 1);
    assertThat(captured.get().relativePath()).isEqualTo("locationBasedList2");
    assertThat(captured.get().queryParameters())
        .containsEntry("mapX", "126.5")
        .containsEntry("mapY", "33.5")
        .containsEntry("radius", "1000")
        .doesNotContainKeys("serviceKey", "query", "rawPayload");

    client.fetch(DiscoveryImportCommand.keyword("성산 일출봉", 2, "keyword"), 1);
    assertThat(captured.get().relativePath()).isEqualTo("searchKeyword2");
    assertThat(captured.get().queryParameters())
        .containsEntry("keyword", "성산 일출봉")
        .containsEntry("lDongRegnCd", "50");

    client.fetch(DiscoveryImportCommand.stay(2, "stay"), 1);
    assertThat(captured.get().relativePath()).isEqualTo("searchStay2");
    assertThat(captured.get().queryParameters()).containsEntry("lDongRegnCd", "50");
    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TOUR_SEARCH_STAY);
  }
}
