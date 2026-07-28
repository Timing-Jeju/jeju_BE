package com.timingjeju.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class NaverProviderAccessTokenResolverTest {

  private final NaverProviderAccessTokenResolver resolver = new NaverProviderAccessTokenResolver();

  @Test
  void 하나의_정확한_Bearer_header에서만_opaque_token을_읽는다() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/auth/social/naver/userinfo");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token");

    assertThat(resolver.resolve(request)).isEqualTo("opaque-provider-token");
  }

  @Test
  void 누락_중복_공백_query_form_token은_모두_거부한다() {
    MockHttpServletRequest missing = new MockHttpServletRequest();
    MockHttpServletRequest duplicate = new MockHttpServletRequest();
    duplicate.addHeader(HttpHeaders.AUTHORIZATION, "Bearer first");
    duplicate.addHeader(HttpHeaders.AUTHORIZATION, "Bearer second");
    MockHttpServletRequest whitespace = new MockHttpServletRequest();
    whitespace.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token with-space");
    MockHttpServletRequest query = new MockHttpServletRequest();
    query.setQueryString("access_token=not-allowed");
    query.addHeader(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token");
    MockHttpServletRequest form = new MockHttpServletRequest();
    form.setContentType("application/x-www-form-urlencoded");
    form.setContent("access_token=not-allowed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    form.addHeader(HttpHeaders.AUTHORIZATION, "Bearer opaque-provider-token");

    assertInvalid(missing);
    assertInvalid(duplicate);
    assertInvalid(whitespace);
    assertInvalid(query);
    assertInvalid(form);
  }

  private void assertInvalid(MockHttpServletRequest request) {
    assertThatThrownBy(() -> resolver.resolve(request))
        .isInstanceOf(NaverUserInfoException.class)
        .extracting(exception -> ((NaverUserInfoException) exception).code().name())
        .isEqualTo("PROVIDER_TOKEN_INVALID");
  }
}
