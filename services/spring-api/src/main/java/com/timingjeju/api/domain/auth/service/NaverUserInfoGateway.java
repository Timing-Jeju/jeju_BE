package com.timingjeju.api.domain.auth.service;

import java.util.Map;

@FunctionalInterface
public interface NaverUserInfoGateway {

  Map<String, Object> getUserInfo(String providerAccessToken);
}
