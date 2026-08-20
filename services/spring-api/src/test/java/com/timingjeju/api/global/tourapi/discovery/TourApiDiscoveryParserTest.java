package com.timingjeju.api.global.tourapi.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryOperation;
import com.timingjeju.api.application.tourapi.place.PlaceRejectReason;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TourApiDiscoveryParserTest {

  private final TourApiDiscoveryParser parser = new TourApiDiscoveryParser(new ObjectMapper());

  @Test
  void stay는_contenttypeid_32만_정규화하고_다른_type은_사유와_함께_거부한다() {
    String payload =
        """
        {"response":{"header":{"resultCode":"0000"},"body":{"pageNo":1,"numOfRows":100,"totalCount":2,
        "items":{"item":[
          {"contentid":"1","contenttypeid":"32","title":"제주 호텔","mapx":"126.5","mapy":"33.5","lDongRegnCd":"50"},
          {"contentid":"2","contenttypeid":"12","title":"관광지","mapx":"126.5","mapy":"33.5","lDongRegnCd":"50"}
        ]}}}}
        """;

    var page =
        parser.parse(
            DiscoveryOperation.STAY,
            SnapshotPayloadFormat.JSON,
            payload.getBytes(StandardCharsets.UTF_8));

    assertThat(page.places()).extracting(place -> place.contentId()).containsExactly("1");
    assertThat(page.rejectedReasons()).containsEntry(PlaceRejectReason.INVALID_CONTENT_TYPE, 1);
  }
}
