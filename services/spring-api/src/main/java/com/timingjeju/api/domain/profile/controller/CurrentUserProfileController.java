package com.timingjeju.api.domain.profile.controller;

import com.timingjeju.api.application.profile.service.CurrentUserProfileService;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.profile.controller.docs.CurrentUserProfileApiDocs;
import com.timingjeju.api.domain.profile.dto.request.CurrentUserProfilePatchRequest;
import com.timingjeju.api.domain.profile.dto.response.CurrentUserProfileResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserProfileController implements CurrentUserProfileApiDocs {

  private final CurrentUserProfileService profiles;
  private final CurrentUserAccessor currentUsers;

  public CurrentUserProfileController(
      CurrentUserProfileService profiles, CurrentUserAccessor currentUsers) {
    this.profiles = profiles;
    this.currentUsers = currentUsers;
  }

  @Override
  @GetMapping
  public CurrentUserProfileResponse read() {
    return CurrentUserProfileResponse.from(profiles.read(currentUsers.getRequired()));
  }

  @Override
  @PatchMapping
  public CurrentUserProfileResponse update(@RequestBody CurrentUserProfilePatchRequest request) {
    return CurrentUserProfileResponse.from(
        profiles.update(currentUsers.getRequired(), request.toCommand()));
  }
}
