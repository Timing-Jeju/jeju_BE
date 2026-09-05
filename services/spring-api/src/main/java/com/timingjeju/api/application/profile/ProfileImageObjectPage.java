package com.timingjeju.api.application.profile;

import java.util.List;

public record ProfileImageObjectPage(List<String> objectKeys, boolean hasMore) {

  public ProfileImageObjectPage {
    objectKeys = List.copyOf(objectKeys);
  }
}
