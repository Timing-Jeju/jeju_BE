package com.timingjeju.api.global.staypolicy;

public final class StayPolicyPublicationException extends RuntimeException {
  private StayPolicyPublicationException(String message) {
    super(message);
  }

  static StayPolicyPublicationException stale(String expected, String actual) {
    return new StayPolicyPublicationException(
        "Stay policy expected active version " + expected + " but found " + actual);
  }

  static StayPolicyPublicationException collision(String version) {
    return new StayPolicyPublicationException(
        "Stay policy version " + version + " has a payload hash collision");
  }
}
