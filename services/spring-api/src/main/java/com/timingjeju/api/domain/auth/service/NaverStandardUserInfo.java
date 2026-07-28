package com.timingjeju.api.domain.auth.service;

public record NaverStandardUserInfo(
    String sub, String email, String name, String preferredUsername, String picture) {}
