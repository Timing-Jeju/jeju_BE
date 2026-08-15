package com.timingjeju.api.global.tourapi.reference;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeOperation;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSource;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSourceResponse;
import com.timingjeju.api.global.externalapi.ExternalApiExecutor;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiRequest;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TourApiReferenceCodeClient implements ReferenceCodeSource {

  private final ReferenceCodeHttpExecutor executor;

  @Autowired
  public TourApiReferenceCodeClient(ExternalApiExecutor executor) {
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

  TourApiReferenceCodeClient(ReferenceCodeHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
  }

  @Override
  public ReferenceCodeSourceResponse fetch(ReferenceCodeOperation operation) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    Map<String, String> query = new LinkedHashMap<>();
    query.put("numOfRows", "1000");
    query.put("pageNo", "1");
    query.put("MobileOS", "ETC");
    query.put("MobileApp", "TimingJeju");
    query.put("_type", "json");
    if (operation == ReferenceCodeOperation.LDONG) {
      query.put("lDongRegnCd", "50");
    }
    ReferenceCodeHttpRequest request =
        new ReferenceCodeHttpRequest(
            externalOperation(operation),
            operation.endpointPath(),
            query,
            ExternalApiResponseFormat.JSON);
    return new ReferenceCodeSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }

  private static ExternalApiOperation externalOperation(ReferenceCodeOperation operation) {
    return operation == ReferenceCodeOperation.LDONG
        ? ExternalApiOperation.TOUR_LDONG_CODE
        : ExternalApiOperation.TOUR_CLASSIFICATION_CODE;
  }
}
