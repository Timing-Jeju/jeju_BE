package com.timingjeju.api.domain.places.service;

import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.StayPolicyResolutionException;
import com.timingjeju.api.application.staypolicy.StayPolicyResolver;
import com.timingjeju.api.application.text.PublicPlainTextNormalizer;
import com.timingjeju.api.domain.places.dto.response.NearbyStop;
import com.timingjeju.api.domain.places.dto.response.PlaceContact;
import com.timingjeju.api.domain.places.dto.response.PlaceDetailResponse;
import com.timingjeju.api.domain.places.dto.response.PlaceImage;
import com.timingjeju.api.domain.places.dto.response.PlaceLocation;
import com.timingjeju.api.domain.places.dto.response.PlaceOperations;
import com.timingjeju.api.domain.places.dto.response.SavedPlaceState;
import com.timingjeju.api.domain.places.exception.PlaceDetailException;
import com.timingjeju.api.domain.places.exception.PlaceDetailUnavailableException;
import com.timingjeju.api.domain.places.model.PlaceDetailImageRow;
import com.timingjeju.api.domain.places.model.PlaceDetailNearbyStopRow;
import com.timingjeju.api.domain.places.model.PlaceDetailSnapshot;
import com.timingjeju.api.domain.places.repository.PlaceDetailRepository;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceDetailService {

  private final PlaceDetailRepository repository;
  private final StayPolicyResolver stayPolicyResolver;
  private final PublicPlainTextNormalizer publicText;

  public PlaceDetailService(
      PlaceDetailRepository repository,
      StayPolicyResolver stayPolicyResolver,
      PublicPlainTextNormalizer publicText) {
    this.repository = repository;
    this.stayPolicyResolver = stayPolicyResolver;
    this.publicText = publicText;
  }

  @Transactional(readOnly = true)
  public PlaceDetailResponse detail(UUID placeId, Optional<UUID> currentUserId) {
    PlaceDetailSnapshot snapshot;
    try {
      snapshot =
          repository
              .find(placeId, currentUserId)
              .orElseThrow(() -> new PlaceDetailException("PLACE_NOT_FOUND"));
    } catch (PlaceDetailUnavailableException failure) {
      throw new PlaceDetailException("PLACE_DATA_UNAVAILABLE");
    }
    RecommendedStay stay;
    try {
      stay = stayPolicyResolver.resolve(snapshot.placeId(), snapshot.category());
    } catch (StayPolicyResolutionException failure) {
      throw new PlaceDetailException("PLACE_DATA_UNAVAILABLE");
    }
    List<PlaceImage> images =
        snapshot.images().stream()
            .map(PlaceDetailService::image)
            .flatMap(Optional::stream)
            .toList();
    URI thumbnail = images.isEmpty() ? null : images.getFirst().thumbnailUrl();
    PlaceOperations operations =
        new PlaceOperations(
            publicText.normalize(snapshot.operatingHoursText()),
            publicText.normalize(snapshot.closedDaysText()),
            publicText.normalize(snapshot.parkingText()),
            publicText.normalize(snapshot.admissionFeeText()));
    return new PlaceDetailResponse(
        snapshot.placeId(),
        snapshot.contentId(),
        snapshot.name(),
        snapshot.category(),
        snapshot.regionCode(),
        snapshot.regionLabel(),
        snapshot.address(),
        new PlaceLocation(snapshot.latitude(), snapshot.longitude()),
        thumbnail,
        stay.minutes(),
        stay.source().name().toLowerCase(Locale.ROOT),
        stay.policyVersion(),
        stay.effectiveAt(),
        stay.updatedAt(),
        summary(operations),
        new SavedPlaceState(snapshot.saved(), snapshot.memo(), snapshot.tags()),
        snapshot.overview(),
        new PlaceContact(
            publicText.normalize(snapshot.phone()), publicUri(snapshot.homepageUrl()).orElse(null)),
        operations,
        images,
        snapshot.nearbyStops().stream().map(PlaceDetailService::nearbyStop).toList());
  }

  private static NearbyStop nearbyStop(PlaceDetailNearbyStopRow row) {
    return new NearbyStop(
        row.stopId(),
        row.stopName(),
        row.distanceMeters(),
        row.walkMinutes(),
        row.linkMethod(),
        row.provider(),
        row.observedAt(),
        row.expiresAt(),
        row.stale());
  }

  private static Optional<PlaceImage> image(PlaceDetailImageRow row) {
    Optional<URI> url = publicUri(row.url());
    if (url.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new PlaceImage(
            url.get(),
            publicUri(row.thumbnailUrl()).orElse(null),
            row.provider(),
            row.observedAt(),
            row.expiresAt(),
            row.stale()));
  }

  static Optional<URI> publicUri(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      URI uri = URI.create(value.trim());
      String scheme = uri.getScheme();
      if (uri.isAbsolute()
          && uri.getHost() != null
          && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
        return Optional.of(uri);
      }
    } catch (IllegalArgumentException ignored) {
      // Invalid provider URLs are omitted from the public projection.
    }
    return Optional.empty();
  }

  private String summary(PlaceOperations operations) {
    String joined =
        java.util.stream.Stream.of(
                operations.operatingHoursText(),
                operations.closedDaysText(),
                operations.parkingText(),
                operations.admissionFeeText())
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.joining(" · "));
    return publicText.normalize(joined);
  }
}
