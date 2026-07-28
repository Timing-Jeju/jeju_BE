package com.timingjeju.api.domain.auth.service;

import com.timingjeju.api.domain.auth.config.SocialLoginProperties;
import java.util.List;

public final class SocialLoginCatalogService {

  private final List<SocialLoginProvider> providers;

  public SocialLoginCatalogService(SocialLoginProperties properties) {
    providers =
        SocialLoginProvider.valuesAsList().stream()
            .filter(provider -> properties.providerIds().contains(provider.id()))
            .toList();
  }

  public List<SocialLoginProvider> getProviders() {
    return providers;
  }
}
