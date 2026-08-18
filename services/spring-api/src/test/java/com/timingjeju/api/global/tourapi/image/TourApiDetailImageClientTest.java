package com.timingjeju.api.global.tourapi.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.tourapi.image.DetailImageRequestContract;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TourApiDetailImageClientTest {
  @Test
  void detailImage2는_전체_이미지와_fixed_page를_요청하고_credential을_전달하지_않는다() {
    AtomicReference<DetailImageHttpRequest> captured = new AtomicReference<>();
    var client =
        new TourApiDetailImageClient(
            request -> {
              captured.set(request);
              return "{}".getBytes(StandardCharsets.UTF_8);
            });

    client.fetch("100", 2);

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TOUR_DETAIL_IMAGE);
    assertThat(captured.get().relativePath()).isEqualTo("detailImage2");
    assertThat(captured.get().queryParameters())
        .containsEntry("contentId", "100")
        .containsEntry("imageYN", "Y")
        .containsEntry("pageNo", "2")
        .containsEntry("numOfRows", Integer.toString(DetailImageRequestContract.PAGE_SIZE))
        .containsEntry("_type", "json")
        .doesNotContainKeys("subImageYN", "serviceKey", "apiKey", "Authorization");
  }
}
