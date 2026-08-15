package com.timingjeju.api.global.tourapi.reference;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeOperation;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TourApiReferenceCodeClientTest {

  @Test
  void 법정동_client는_고정_endpoint와_제주_scope를_사용하고_secret_query를_받지_않는다() {
    AtomicReference<ReferenceCodeHttpRequest> captured = new AtomicReference<>();
    TourApiReferenceCodeClient client =
        new TourApiReferenceCodeClient(
            request -> {
              captured.set(request);
              return "fixture".getBytes(StandardCharsets.UTF_8);
            });

    var response = client.fetch(ReferenceCodeOperation.LDONG);

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TOUR_LDONG_CODE);
    assertThat(captured.get().relativePath()).isEqualTo("ldongCode2");
    assertThat(captured.get().queryParameters())
        .containsEntry("lDongRegnCd", "50")
        .containsEntry("_type", "json")
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization");
    assertThat(response.format()).isEqualTo(SnapshotPayloadFormat.JSON);
  }

  @Test
  void 관광분류_client는_고정_endpoint만_선택한다() {
    AtomicReference<ReferenceCodeHttpRequest> captured = new AtomicReference<>();
    TourApiReferenceCodeClient client =
        new TourApiReferenceCodeClient(
            request -> {
              captured.set(request);
              return new byte[] {1};
            });

    client.fetch(ReferenceCodeOperation.CLASSIFICATION);

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TOUR_CLASSIFICATION_CODE);
    assertThat(captured.get().relativePath()).isEqualTo("lclsSystmCode2");
  }
}
