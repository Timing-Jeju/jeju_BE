package com.timingjeju.api.global.tago.stop;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoStopClientTest {

  private static final String OFFICIAL_TAGO_BASE_URL = "https://apis.data.go.kr/1613000";

  @Test
  void city_code_client는_고정_endpoint와_query만_사용하고_credential을_받지_않는다() {
    AtomicReference<TagoStopHttpRequest> captured = new AtomicReference<>();
    TagoStopClient client = client(captured);

    var response = client.fetchCityCodes();

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TAGO_CITY_CODE);
    assertThat(captured.get().relativePath()).isEqualTo("BusSttnInfoInqireService/getCtyCodeList");
    assertThat(officialRequestUri(captured.get()).getPath())
        .isEqualTo("/1613000/BusSttnInfoInqireService/getCtyCodeList");
    assertThat(captured.get().queryParameters())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of("_type", "json", "pageNo", "1", "numOfRows", "100"))
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization", "url");
    assertThat(response.format()).isEqualTo(SnapshotPayloadFormat.JSON);
  }

  @Test
  void station_client는_제주_city와_page만_고정_query로_보낸다() {
    AtomicReference<TagoStopHttpRequest> captured = new AtomicReference<>();
    TagoStopClient client = client(captured);

    client.fetchStations("39", 2);

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.TAGO_STATION_LIST);
    assertThat(captured.get().relativePath()).isEqualTo("BusSttnInfoInqireService/getSttnNoList");
    assertThat(officialRequestUri(captured.get()).getPath())
        .isEqualTo("/1613000/BusSttnInfoInqireService/getSttnNoList");
    assertThat(captured.get().queryParameters())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of("_type", "json", "cityCode", "39", "pageNo", "2", "numOfRows", "100"))
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization", "url");
  }

  private static URI officialRequestUri(TagoStopHttpRequest request) {
    return URI.create(OFFICIAL_TAGO_BASE_URL + "/" + request.relativePath());
  }

  private static TagoStopClient client(AtomicReference<TagoStopHttpRequest> captured) {
    return new TagoStopClient(
        request -> {
          captured.set(request);
          return "fixture".getBytes(StandardCharsets.UTF_8);
        });
  }
}
