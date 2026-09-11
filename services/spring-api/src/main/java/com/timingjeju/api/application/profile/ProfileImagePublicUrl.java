package com.timingjeju.api.application.profile;

@FunctionalInterface
public interface ProfileImagePublicUrl {
  String resolve(String objectKey);
}
