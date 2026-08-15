package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.DetailIntroSource;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.global.externalapi.ExternalApiExecutor;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiRequest;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TourApiDetailIntroClient implements DetailIntroSource {
  private final PlaceDetailHttpExecutor executor;

  @Autowired
  public TourApiDetailIntroClient(ExternalApiExecutor executor) {
    this(
        request ->
            executor.execute(
                ExternalApiRequest.get(
                    request.operation(),
                    request.relativePath(),
                    request.queryParameters(),
                    request.format()),
                body -> body));
  }

  TourApiDetailIntroClient(PlaceDetailHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public DetailSourceResponse fetch(String contentId, String contentTypeId) {
    if (contentTypeId == null || contentTypeId.isBlank())
      throw new IllegalArgumentException("contentTypeId는 필수입니다.");
    Map<String, String> query = TourApiDetailCommonClient.base(contentId);
    query.put("contentTypeId", contentTypeId);
    var request =
        new PlaceDetailHttpRequest(
            ExternalApiOperation.TOUR_DETAIL_INTRO,
            "/detailIntro2",
            query,
            ExternalApiResponseFormat.JSON);
    return new DetailSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }
}
