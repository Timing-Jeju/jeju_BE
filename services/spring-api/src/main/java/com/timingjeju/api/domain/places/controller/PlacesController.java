package com.timingjeju.api.domain.places.controller;

import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.places.controller.docs.PlacesApiDocs;
import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import com.timingjeju.api.domain.places.dto.response.PlaceDetailResponse;
import com.timingjeju.api.domain.places.dto.response.PlacesListResponse;
import com.timingjeju.api.domain.places.service.PlaceDetailService;
import com.timingjeju.api.domain.places.service.PlaceListService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/places")
public class PlacesController implements PlacesApiDocs {

  private final PlaceListService listService;
  private final PlaceDetailService detailService;
  private final CurrentUserAccessor currentUsers;

  public PlacesController(
      PlaceListService listService,
      PlaceDetailService detailService,
      CurrentUserAccessor currentUsers) {
    this.listService = listService;
    this.detailService = detailService;
    this.currentUsers = currentUsers;
  }

  @Override
  @GetMapping
  public PlacesListResponse list(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false) Double lat,
      @RequestParam(required = false) Double lng,
      @RequestParam(required = false) Integer radiusMeters,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) Boolean savedOnly) {
    PlacesListQuery normalized =
        PlacesListQuery.of(
            query, category, regionCode, lat, lng, radiusMeters, cursor, size, savedOnly);
    return listService.list(
        normalized, currentUsers.getOptional().map(currentUser -> currentUser.userId()));
  }

  @Override
  @GetMapping("/{placeId}")
  public PlaceDetailResponse read(@PathVariable String placeId) {
    return detailService.detail(
        UUID.fromString(placeId),
        currentUsers.getOptional().map(currentUser -> currentUser.userId()));
  }
}
