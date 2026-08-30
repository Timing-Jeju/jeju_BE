package com.timingjeju.api.domain.savedplaces.controller;

import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.savedplaces.controller.docs.SavedPlacesApiDocs;
import com.timingjeju.api.domain.savedplaces.dto.CreateSavedPlaceRequest;
import com.timingjeju.api.domain.savedplaces.dto.PatchSavedPlaceRequest;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceResponse;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlacesListResponse;
import com.timingjeju.api.domain.savedplaces.model.CanonicalSavedPlaceId;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesQuery;
import com.timingjeju.api.domain.savedplaces.service.SavedPlaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/saved-places")
public class SavedPlacesController implements SavedPlacesApiDocs {
  private final SavedPlaceService service;
  private final CurrentUserAccessor users;

  public SavedPlacesController(SavedPlaceService service, CurrentUserAccessor users) {
    this.service = service;
    this.users = users;
  }

  @GetMapping
  public SavedPlacesListResponse list(
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size,
      HttpServletRequest httpRequest) {
    validateQuery(
        httpRequest,
        java.util.Set.of("tag", "category", "regionCode", "sort", "cursor", "size"),
        "INVALID_QUERY_PARAMETER");
    var result =
        service.list(
            users.getRequired().userId(),
            SavedPlacesQuery.of(tag, category, regionCode, sort, cursor, size));
    return new SavedPlacesListResponse(
        result.items().stream().map(SavedPlaceResponse::from).toList(),
        new SavedPlacesListResponse.CursorPage(
            result.size(), result.hasNext(), result.nextCursor()));
  }

  @PostMapping
  public ResponseEntity<byte[]> create(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody CreateSavedPlaceRequest request,
      HttpServletRequest httpRequest) {
    validateQuery(httpRequest, java.util.Set.of(), "INVALID_REQUEST");
    var result = service.create(users.getRequired().userId(), key, request.toCommand());
    var snapshot = result.snapshot();
    return ResponseEntity.status(snapshot.status())
        .contentType(MediaType.parseMediaType(snapshot.contentType()))
        .header("Location", snapshot.location())
        .header("ETag", snapshot.etag())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .body(snapshot.body());
  }

  @PatchMapping("/{placeId}")
  public ResponseEntity<SavedPlaceResponse> patch(
      @PathVariable String placeId,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody PatchSavedPlaceRequest request,
      HttpServletRequest httpRequest) {
    validateQuery(httpRequest, java.util.Set.of(), "INVALID_REQUEST");
    var result =
        service.patch(
            users.getRequired().userId(),
            CanonicalSavedPlaceId.parse(placeId),
            ifMatch,
            request.toCommand());
    return ResponseEntity.ok().eTag(result.etag()).body(SavedPlaceResponse.from(result.place()));
  }

  @DeleteMapping("/{placeId}")
  public ResponseEntity<Void> delete(@PathVariable String placeId, HttpServletRequest httpRequest) {
    validateQuery(httpRequest, java.util.Set.of(), "INVALID_REQUEST");
    try {
      if (httpRequest.getInputStream().read() != -1) {
        throw SavedPlaceException.invalidRequest();
      }
    } catch (java.io.IOException exception) {
      throw SavedPlaceException.invalidRequest();
    }
    service.delete(users.getRequired().userId(), CanonicalSavedPlaceId.parse(placeId));
    return ResponseEntity.noContent().build();
  }

  private static void validateQuery(
      HttpServletRequest request, java.util.Set<String> allowed, String problemCode) {
    for (var entry : request.getParameterMap().entrySet()) {
      if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
        throw SavedPlaceException.of(problemCode);
      }
    }
  }
}
