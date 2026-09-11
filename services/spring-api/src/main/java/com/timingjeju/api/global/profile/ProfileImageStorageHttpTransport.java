package com.timingjeju.api.global.profile;

import java.net.http.HttpRequest;

interface ProfileImageStorageHttpTransport {

  ProfileImageStorageHttpResponse exchange(
      HttpRequest request, byte[] requestBody, int maximumBodyBytes);
}
