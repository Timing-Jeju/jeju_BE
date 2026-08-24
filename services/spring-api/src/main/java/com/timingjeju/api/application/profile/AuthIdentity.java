package com.timingjeju.api.application.profile;

public record AuthIdentity(
    String provider, String providerId, String email, String nickname, String profileImageUrl) {}
