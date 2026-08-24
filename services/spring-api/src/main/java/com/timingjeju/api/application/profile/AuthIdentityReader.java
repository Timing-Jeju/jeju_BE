package com.timingjeju.api.application.profile;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface AuthIdentityReader {

  List<AuthIdentity> readByUserId(UUID userId);
}
