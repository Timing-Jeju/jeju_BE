package com.timingjeju.api.domain.auth.service;

public record NaverStandardUserInfo(
    String sub,
    String email,
    boolean emailVerified,
    String name,
    String preferredUsername,
    String picture) {}
