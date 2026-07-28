package com.timingjeju.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.auth.config.SocialLoginProperties;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SocialLoginCatalogServiceTest {

  @Test
  void 지원하는_공급자만_고정_순서와_공개_표시명으로_반환한다() {
    SocialLoginCatalogService service =
        new SocialLoginCatalogService(new SocialLoginProperties(List.of("custom:naver", "google")));

    assertThat(service.getProviders())
        .extracting(provider -> provider.id() + ":" + provider.displayName())
        .containsExactly("google:Google", "custom:naver:Naver");
  }
}
