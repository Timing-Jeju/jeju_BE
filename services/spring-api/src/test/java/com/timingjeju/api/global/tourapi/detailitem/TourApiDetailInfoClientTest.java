package com.timingjeju.api.global.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.tourapi.detailitem.DetailInfoRequestContract;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TourApiDetailInfoClientTest {
  @Test
  void detailInfo2는_content_식별자와_고정_format만_전달하고_credential은_전달하지_않는다() {
    AtomicReference<DetailInfoHttpRequest> captured = new AtomicReference<>();
    var client =
        new TourApiDetailInfoClient(
            request -> {
              captured.set(request);
              return "{}".getBytes(StandardCharsets.UTF_8);
            });

    client.fetch("100", "12", 2);

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TOUR_DETAIL_INFO);
    assertThat(captured.get().relativePath()).isEqualTo("detailInfo2");
    assertThat(captured.get().queryParameters())
        .containsEntry("contentId", "100")
        .containsEntry("contentTypeId", "12")
        .containsEntry("pageNo", "2")
        .containsEntry("numOfRows", Integer.toString(DetailInfoRequestContract.PAGE_SIZE))
        .containsEntry("_type", "json")
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization");
  }
}
