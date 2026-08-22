package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.global.externalapi.ExternalApiFailureCode;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoArrivalClientTest {

  @Test
  void 정류장_도착_client는_공식_endpoint와_stop_query만_사용한다() {
    AtomicReference<TagoArrivalHttpRequest> captured = new AtomicReference<>();
    byte[] exact = " {\"response\": {\"body\":1.00}} ".getBytes(StandardCharsets.UTF_8);
    TagoArrivalClient client =
        new TagoArrivalClient(
            request -> {
              captured.set(request);
              return exact;
            });

    var response = client.fetch("39", "JEP123");

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TAGO_ARRIVAL);
    assertThat(captured.get().relativePath())
        .isEqualTo("ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList");
    assertThat(captured.get().queryParameters())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "_type", "json",
                "cityCode", "39",
                "nodeId", "JEP123",
                "pageNo", "1",
                "numOfRows", "100"))
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization", "url");
    assertThat(response.payload()).isSameAs(exact);
  }

  @Test
  void HTTP_429와_timeout을_서로_다른_stable_error로_변환한다() {
    assertThat(TagoArrivalClient.mapFailure(ExternalApiFailureCode.RETRY_EXHAUSTED, 429).code())
        .isEqualTo(TagoArrivalException.Code.RATE_LIMITED);
    assertThat(TagoArrivalClient.mapFailure(ExternalApiFailureCode.TOTAL_TIMEOUT, null).code())
        .isEqualTo(TagoArrivalException.Code.TIMEOUT);
  }
}
