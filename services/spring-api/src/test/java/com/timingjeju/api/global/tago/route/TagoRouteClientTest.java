package com.timingjeju.api.global.tago.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoRouteClientTest {
  @Test
  void official_route_operations는_고정_path와_query_allowlist만_사용한다() {
    List<TagoRouteHttpRequest> requests = new ArrayList<>();
    TagoRouteClient client =
        new TagoRouteClient(
            request -> {
              requests.add(request);
              return new byte[0];
            });

    client.fetchRouteList("39", "101", 2);
    client.fetchRouteDetail("39", "JEB405410111");
    client.fetchRouteStops("39", "JEB405410111", 3);

    assertThat(requests).hasSize(3);
    assertThat(requests.get(0).operation()).isEqualTo(ExternalApiOperation.TAGO_ROUTE_LIST);
    assertThat(requests.get(0).relativePath())
        .isEqualTo("BusRouteInfoInqireService/getRouteNoList");
    assertThat(requests.get(0).queryParameters())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of(
                "_type",
                "json",
                "cityCode",
                "39",
                "routeNo",
                "101",
                "pageNo",
                "2",
                "numOfRows",
                "100"));
    assertThat(requests.get(1).operation()).isEqualTo(ExternalApiOperation.TAGO_ROUTE_DETAIL);
    assertThat(requests.get(1).relativePath())
        .isEqualTo("BusRouteInfoInqireService/getRouteInfoIem");
    assertThat(requests.get(1).queryParameters())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of("_type", "json", "cityCode", "39", "routeId", "JEB405410111"));
    assertThat(requests.get(2).operation()).isEqualTo(ExternalApiOperation.TAGO_ROUTE_STOPS);
    assertThat(requests.get(2).relativePath())
        .isEqualTo("BusRouteInfoInqireService/getRouteAcctoThrghSttnList");
    assertThat(requests.get(2).queryParameters())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of(
                "_type",
                "json",
                "cityCode",
                "39",
                "routeId",
                "JEB405410111",
                "pageNo",
                "3",
                "numOfRows",
                "100"));
    assertThat(requests)
        .allSatisfy(
            request ->
                assertThat(request.queryParameters())
                    .doesNotContainKeys("serviceKey", "apiKey", "Authorization", "url"));
  }
}
