package com.timingjeju.api.global.tourapi.discovery;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryOperation;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryParser;
import com.timingjeju.api.application.tourapi.place.PlaceListPage;
import com.timingjeju.api.application.tourapi.place.PlaceRejectReason;
import com.timingjeju.api.global.tourapi.place.TourApiPlaceListParser;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class TourApiDiscoveryParser implements DiscoveryParser {

  private static final String LODGING_CONTENT_TYPE = "32";
  private final TourApiPlaceListParser delegate;

  public TourApiDiscoveryParser(ObjectMapper objectMapper) {
    delegate = new TourApiPlaceListParser(objectMapper);
  }

  @Override
  public PlaceListPage parse(
      DiscoveryOperation operation, SnapshotPayloadFormat format, byte[] payload) {
    PlaceListPage parsed = delegate.parse(format, payload);
    if (operation != DiscoveryOperation.STAY) {
      return parsed;
    }
    var lodging =
        parsed.places().stream()
            .filter(place -> LODGING_CONTENT_TYPE.equals(place.contentTypeId()))
            .toList();
    int rejectedType = parsed.places().size() - lodging.size();
    Map<PlaceRejectReason, Integer> rejected = new EnumMap<>(PlaceRejectReason.class);
    rejected.putAll(parsed.rejectedReasons());
    if (rejectedType > 0) {
      rejected.merge(PlaceRejectReason.INVALID_CONTENT_TYPE, rejectedType, Integer::sum);
    }
    return new PlaceListPage(
        parsed.pageNo(),
        parsed.numOfRows(),
        parsed.totalCount(),
        parsed.rawItemCount(),
        lodging,
        rejected);
  }
}
