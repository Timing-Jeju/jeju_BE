package com.timingjeju.api.application.accommodation;

import java.util.UUID;

@FunctionalInterface
public interface AccommodationIdentityGenerator {
  UUID generate();
}
