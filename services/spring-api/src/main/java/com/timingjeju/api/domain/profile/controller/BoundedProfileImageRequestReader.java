package com.timingjeju.api.domain.profile.controller;

import com.timingjeju.api.application.profile.ProfileImageException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

final class BoundedProfileImageRequestReader {

  static final int MAXIMUM_BODY_BYTES = 1024 * 1024;

  byte[] read(HttpServletRequest request) {
    if (request.getContentLengthLong() > MAXIMUM_BODY_BYTES) {
      throw ProfileImageException.invalidRequest();
    }
    try {
      byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
      if (body.length > MAXIMUM_BODY_BYTES) {
        throw ProfileImageException.invalidRequest();
      }
      return body;
    } catch (IOException failure) {
      throw ProfileImageException.invalidRequest();
    }
  }
}
