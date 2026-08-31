package com.timingjeju.api.global.security;

import static com.timingjeju.api.support.http.ProblemDetailsAssertions.problemDetails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.notification.service.NotificationPreferenceService;
import com.timingjeju.api.application.notification.service.PushDeviceService;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.trip.service.TripService;
import com.timingjeju.api.domain.savedplaces.service.SavedPlaceService;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=canonical-required-route-security"
    })
@AutoConfigureMockMvc
class CanonicalRequiredRouteSecurityIntegrationTest {

  private static final List<String> REQUIRED_ROUTES =
      List.of(
          "/api/v1/me",
          "/api/v1/me/saved-places",
          "/api/v1/trips",
          "/api/v1/me/notification-preferences",
          // Issue #49가 route를 병합하면 별도 endpoint 정책 없이 이 공통 filter 계약을 상속한다.
          "/api/v1/trips/44000000-0000-4000-8000-000000000044/schedule");

  @Autowired private MockMvc mvc;
  @MockitoBean private CurrentUserProvisioningService provisioningService;
  @MockitoBean private CurrentUserProfileStore profileStore;
  @MockitoBean private SavedPlaceService savedPlaceService;
  @MockitoBean private TripService tripService;
  @MockitoBean private IdempotencyUseCase idempotencyUseCase;
  @MockitoBean private PushDeviceService pushDeviceService;
  @MockitoBean private NotificationPreferenceService notificationPreferenceService;

  @Test
  void 대표_required_route와_향후_schedule은_missing과_invalid를_controller전에_구분한다() throws Exception {
    for (String route : REQUIRED_ROUTES) {
      assertUnauthorized(
          get(route),
          "AUTHENTICATION_REQUIRED",
          "authentication-required",
          "인증이 필요합니다",
          "로그인 후 다시 요청해 주세요.");
      assertUnauthorized(
          get(route).header(HttpHeaders.AUTHORIZATION, "Bearer malformed"),
          "INVALID_ACCESS_TOKEN",
          "invalid-access-token",
          "인증 정보가 올바르지 않습니다",
          "유효한 인증 정보로 다시 요청해 주세요.");
    }

    verifyNoInteractions(
        provisioningService,
        profileStore,
        savedPlaceService,
        tripService,
        idempotencyUseCase,
        pushDeviceService,
        notificationPreferenceService);
  }

  private void assertUnauthorized(
      MockHttpServletRequestBuilder request,
      String code,
      String typeSuffix,
      String title,
      String detail)
      throws Exception {
    mvc.perform(request)
        .andExpectAll(
            problemDetails(
                401, "https://api.timing-jeju.com/problems/" + typeSuffix, title, code, detail))
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(
            result -> {
              assertThat(result.getResponse().getHeaders(HttpHeaders.WWW_AUTHENTICATE))
                  .containsExactly("Bearer");
              assertThat(result.getResponse().getHeaders("X-Trace-Id")).hasSize(1);
            });
  }
}
