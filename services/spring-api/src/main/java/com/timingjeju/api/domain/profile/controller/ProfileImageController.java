package com.timingjeju.api.domain.profile.controller;

import com.timingjeju.api.application.profile.ProfileImageSnapshot;
import com.timingjeju.api.application.profile.service.ProfileImageService;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.profile.controller.docs.ProfileImageApiDocs;
import com.timingjeju.api.domain.profile.dto.request.ProfileImageRequest;
import com.timingjeju.api.domain.profile.dto.response.ProfileImageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@RestController
@RequestMapping("/api/v1/me/profile-image")
public class ProfileImageController implements ProfileImageApiDocs {

  private final ProfileImageService service;
  private final CurrentUserAccessor currentUsers;
  private final ObjectReader jsonReader;
  private final BoundedProfileImageRequestReader bodyReader;

  public ProfileImageController(
      ProfileImageService service, CurrentUserAccessor currentUsers, ObjectMapper objectMapper) {
    this.service = service;
    this.currentUsers = currentUsers;
    this.jsonReader =
        objectMapper
            .readerFor(ProfileImageRequest.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.bodyReader = new BoundedProfileImageRequestReader();
  }

  @Override
  @GetMapping
  public ResponseEntity<ProfileImageResponse> read(HttpServletRequest httpRequest) {
    rejectQuery(httpRequest);
    ProfileImageSnapshot snapshot = service.read(currentUsers.getRequired());
    return ResponseEntity.ok().eTag(snapshot.etag()).body(ProfileImageResponse.from(snapshot));
  }

  @Override
  @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProfileImageResponse> apply(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest httpRequest) {
    rejectQuery(httpRequest);
    ProfileImageRequest request = parse(bodyReader.read(httpRequest));
    CurrentUser currentUser = currentUsers.getRequired();
    com.timingjeju.api.application.profile.ProfileImageApplyResult result =
        service.applyIdempotent(
            currentUser, idempotencyKey, request.toCommand(currentUser.userId(), ifMatch));
    ProfileImageSnapshot snapshot = result.snapshot();
    return ResponseEntity.ok()
        .eTag(snapshot.etag())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .body(ProfileImageResponse.from(snapshot));
  }

  private ProfileImageRequest parse(byte[] body) {
    try {
      return jsonReader.readValue(body);
    } catch (JacksonException failure) {
      throw com.timingjeju.api.application.profile.ProfileImageException.invalidRequest();
    }
  }

  private static void rejectQuery(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) {
      throw com.timingjeju.api.application.profile.ProfileImageException.invalidRequest();
    }
  }
}
