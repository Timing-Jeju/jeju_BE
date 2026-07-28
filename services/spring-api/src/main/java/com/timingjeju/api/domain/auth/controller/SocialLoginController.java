package com.timingjeju.api.domain.auth.controller;

import com.timingjeju.api.domain.auth.controller.docs.SocialLoginApiDocs;
import com.timingjeju.api.domain.auth.dto.response.SocialLoginProviderResponse;
import com.timingjeju.api.domain.auth.dto.response.SocialLoginProvidersResponse;
import com.timingjeju.api.domain.auth.service.NaverProviderAccessTokenResolver;
import com.timingjeju.api.domain.auth.service.NaverStandardUserInfo;
import com.timingjeju.api.domain.auth.service.NaverUserInfoService;
import com.timingjeju.api.domain.auth.service.SocialLoginCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/social")
public class SocialLoginController implements SocialLoginApiDocs {

  private final SocialLoginCatalogService catalogService;
  private final NaverUserInfoService naverUserInfoService;
  private final NaverProviderAccessTokenResolver accessTokenResolver =
      new NaverProviderAccessTokenResolver();

  public SocialLoginController(
      SocialLoginCatalogService catalogService, NaverUserInfoService naverUserInfoService) {
    this.catalogService = catalogService;
    this.naverUserInfoService = naverUserInfoService;
  }

  @Override
  @GetMapping("/providers")
  public SocialLoginProvidersResponse getProviders() {
    List<SocialLoginProviderResponse> providers =
        catalogService.getProviders().stream().map(SocialLoginProviderResponse::from).toList();
    return new SocialLoginProvidersResponse(providers);
  }

  @Override
  @GetMapping("/naver/userinfo")
  public Map<String, Object> getNaverUserInfo(HttpServletRequest request) {
    String naverCredential = accessTokenResolver.resolve(request);
    NaverStandardUserInfo userInfo = naverUserInfoService.getUserInfo(naverCredential);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("sub", userInfo.sub());
    response.put("email", userInfo.email());
    putIfPresent(response, "name", userInfo.name());
    putIfPresent(response, "preferred_username", userInfo.preferredUsername());
    putIfPresent(response, "picture", userInfo.picture());
    return response;
  }

  private static void putIfPresent(Map<String, Object> response, String field, String value) {
    if (value != null) {
      response.put(field, value);
    }
  }
}
