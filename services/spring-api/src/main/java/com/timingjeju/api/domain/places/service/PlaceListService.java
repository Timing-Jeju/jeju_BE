package com.timingjeju.api.domain.places.service;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.pagination.CursorContext;
import com.timingjeju.api.application.pagination.CursorContextMismatchException;
import com.timingjeju.api.application.pagination.CursorFilterFingerprint;
import com.timingjeju.api.application.pagination.CursorInvalidException;
import com.timingjeju.api.application.pagination.CursorPosition;
import com.timingjeju.api.application.pagination.CursorSort;
import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.StayPolicyResolutionException;
import com.timingjeju.api.application.staypolicy.StayPolicyResolver;
import com.timingjeju.api.application.staypolicy.StayPolicySubject;
import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import com.timingjeju.api.domain.places.dto.response.PlaceCursorPage;
import com.timingjeju.api.domain.places.dto.response.PlaceDataFreshness;
import com.timingjeju.api.domain.places.dto.response.PlaceListItem;
import com.timingjeju.api.domain.places.dto.response.PlaceLocation;
import com.timingjeju.api.domain.places.dto.response.PlacesListResponse;
import com.timingjeju.api.domain.places.exception.PlaceListException;
import com.timingjeju.api.domain.places.exception.PlaceSearchUnavailableException;
import com.timingjeju.api.domain.places.model.PlaceSearchPosition;
import com.timingjeju.api.domain.places.model.PlaceSearchRow;
import com.timingjeju.api.domain.places.repository.PlaceSearchRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceListService {

  private static final String ENDPOINT = "/api/v1/places";
  private static final CursorSort DEFAULT_SORT = CursorSort.asc("normalizedName", "placeId");
  private static final String CURSOR_PREFIX = "plc2.";

  private final PlaceSearchRepository repository;
  private final StayPolicyResolver stayPolicyResolver;
  private final CursorCodec cursorCodec;

  public PlaceListService(
      PlaceSearchRepository repository,
      StayPolicyResolver stayPolicyResolver,
      CursorCodec cursorCodec) {
    this.repository = repository;
    this.stayPolicyResolver = stayPolicyResolver;
    this.cursorCodec = cursorCodec.scoped("places-location-free/v2");
  }

  @Transactional(readOnly = true)
  public PlacesListResponse list(PlacesListQuery query, Optional<UUID> currentUserId) {
    if (query.savedOnly() && currentUserId.isEmpty()) {
      throw new PlaceListException("AUTHENTICATION_REQUIRED");
    }
    CursorContext context = context(query);
    PlaceSearchPosition after = decode(query, context);
    List<PlaceSearchRow> fetched;
    try {
      fetched = repository.search(query, after, currentUserId);
    } catch (PlaceSearchUnavailableException failure) {
      throw dataUnavailable();
    }
    boolean hasNext = fetched.size() > query.size();
    List<PlaceSearchRow> rows = hasNext ? fetched.subList(0, query.size()) : fetched;
    Map<UUID, RecommendedStay> stays;
    try {
      stays =
          stayPolicyResolver.resolveAll(
              rows.stream()
                  .map(row -> new StayPolicySubject(row.placeId(), row.category()))
                  .toList());
    } catch (StayPolicyResolutionException failure) {
      throw dataUnavailable();
    }
    List<PlaceListItem> items =
        rows.stream()
            .map(
                row ->
                    toItem(row, stays.getOrDefault(row.placeId(), RecommendedStay.unavailable())))
            .toList();
    String nextCursor =
        hasNext
            ? CURSOR_PREFIX + cursorCodec.encode(context, cursorPosition(rows.getLast()))
            : null;
    return new PlacesListResponse(items, new PlaceCursorPage(query.size(), hasNext, nextCursor));
  }

  private static PlaceListException dataUnavailable() {
    return new PlaceListException("PLACE_DATA_UNAVAILABLE");
  }

  private CursorContext context(PlacesListQuery query) {
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("query", query.query());
    filters.put("category", query.category());
    filters.put("regionCode", query.regionCode());
    filters.put("size", query.size());
    filters.put("savedOnly", query.savedOnly());
    filters.put("sortProfile", "places-location-free/v2");
    return new CursorContext(ENDPOINT, DEFAULT_SORT, CursorFilterFingerprint.sha256(filters));
  }

  private PlaceSearchPosition decode(PlacesListQuery query, CursorContext context) {
    if (query.cursor() == null) {
      return null;
    }
    try {
      if (!query.cursor().matches("^plc2\\.[A-Za-z0-9_-]{1,2043}$")) {
        throw new CursorInvalidException();
      }
      CursorPosition cursor =
          cursorCodec.decode(query.cursor().substring(CURSOR_PREFIX.length()), context);
      return new PlaceSearchPosition(cursor.sortValue(), UUID.fromString(cursor.tieBreaker()));
    } catch (CursorContextMismatchException exception) {
      throw new PlaceListException("CURSOR_CONTEXT_MISMATCH");
    } catch (CursorInvalidException | IllegalArgumentException exception) {
      throw new PlaceListException("INVALID_CURSOR");
    }
  }

  private static CursorPosition cursorPosition(PlaceSearchRow row) {
    return new CursorPosition(row.normalizedName(), row.placeId().toString());
  }

  private static PlaceListItem toItem(PlaceSearchRow row, RecommendedStay stay) {
    return new PlaceListItem(
        row.placeId(),
        row.contentId(),
        row.name(),
        row.category(),
        row.regionCode(),
        row.regionLabel(),
        row.address(),
        new PlaceLocation(row.lat(), row.lng()),
        row.thumbnailUrl(),
        stay.minutes(),
        stay.source().value(),
        stay.policyVersion(),
        stay.effectiveAt(),
        stay.updatedAt(),
        row.operationsSummary(),
        new PlaceDataFreshness(row.provider(), row.observedAt(), row.expiresAt(), row.stale()),
        row.saved(),
        row.memo(),
        row.tags());
  }
}
