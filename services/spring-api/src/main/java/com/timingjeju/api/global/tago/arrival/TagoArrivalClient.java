package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSource;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import com.timingjeju.api.global.externalapi.ExternalApiException;
import com.timingjeju.api.global.externalapi.ExternalApiExecutor;
import com.timingjeju.api.global.externalapi.ExternalApiFailureCode;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiRequest;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TagoArrivalClient implements TagoArrivalSource {
  private static final String PATH = "ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList";
  private final TagoArrivalHttpExecutor executor;

  @Autowired
  public TagoArrivalClient(ExternalApiExecutor executor) {
    Objects.requireNonNull(executor, "executor는 필수입니다.");
    this.executor =
        request -> {
          try {
            return executor.execute(
                ExternalApiRequest.get(
                    request.operation(),
                    request.relativePath(),
                    request.queryParameters(),
                    request.format()),
                body -> body);
          } catch (ExternalApiException failure) {
            throw map(failure);
          }
        };
  }

  TagoArrivalClient(TagoArrivalHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
  }

  @Override
  public TagoArrivalSourceResponse fetch(String cityCode, String nodeId) {
    if (cityCode == null || cityCode.isBlank() || nodeId == null || nodeId.isBlank()) {
      throw TagoArrivalException.invalidRequest();
    }
    Map<String, String> query = new LinkedHashMap<>();
    query.put("numOfRows", "100");
    query.put("pageNo", "1");
    query.put("_type", "json");
    query.put("cityCode", cityCode.strip());
    query.put("nodeId", nodeId.strip());
    byte[] payload =
        executor.execute(
            new TagoArrivalHttpRequest(
                ExternalApiOperation.TAGO_ARRIVAL,
                PATH,
                Map.copyOf(query),
                ExternalApiResponseFormat.JSON));
    return new TagoArrivalSourceResponse(payload, SnapshotPayloadFormat.JSON);
  }

  private static TagoArrivalException map(ExternalApiException failure) {
    return mapFailure(failure.code(), failure.status());
  }

  static TagoArrivalException mapFailure(ExternalApiFailureCode code, Integer status) {
    if (status != null && status == 429) {
      return TagoArrivalException.rateLimited();
    }
    return switch (code) {
      case CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT -> TagoArrivalException.timeout();
      default -> TagoArrivalException.providerUnavailable();
    };
  }
}
