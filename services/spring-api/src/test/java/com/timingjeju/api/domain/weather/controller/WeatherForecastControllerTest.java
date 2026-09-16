package com.timingjeju.api.domain.weather.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.domain.weather.dto.response.WeatherForecastResponse;
import com.timingjeju.api.domain.weather.dto.response.WeatherGridResponse;
import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import com.timingjeju.api.domain.weather.service.WeatherForecastQueryService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
@Tag("slice")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=weather-controller"
    })
@AutoConfigureMockMvc
class WeatherForecastControllerTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final String PATH = "/api/v1/weather/forecast";

  @Autowired private MockMvc mvc;
  @MockitoBean private WeatherForecastQueryService service;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void successResponse() {
    when(service.forecast(any(), any())).thenReturn(success());
  }

  @Test
  void anonymous와_valid_optional_JWT는_동일한_닫힌_200_projection을_받는다() throws Exception {
    mvc.perform(validRequest())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractVersion").value("2.0.0"))
        .andExpect(jsonPath("$.grid.nx").value(60))
        .andExpect(jsonPath("$.grid.ny").value(37))
        .andExpect(jsonPath("$.observedAt").value("2026-08-03T14:10:00+09:00"))
        .andExpect(jsonPath("$.expiresAt").value("2026-08-03T14:20:00+09:00"))
        .andExpect(jsonPath("$.stale").value(true))
        .andExpect(jsonPath("$.fallbackUsed").value(false))
        .andExpect(jsonPath("$.rawPayload").doesNotExist())
        .andExpect(jsonPath("$.lat").doesNotExist())
        .andExpect(jsonPath("$.lng").doesNotExist());

    mvc.perform(
            validRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grid.nx").value(60));
  }

  @Test
  void anonymous_placeId는_공개_선택자로_200이고_owner를_전달하지_않는다() throws Exception {
    UUID placeId = UUID.fromString("10000000-0000-4000-8000-000000000001");

    mvc.perform(
            get(PATH)
                .queryParam("placeId", placeId.toString())
                .queryParam("dateTime", "2026-08-03T15:00:00+09:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractVersion").value("2.0.0"));

    org.mockito.Mockito.verify(service)
        .forecast(
            org.mockito.ArgumentMatchers.argThat(q -> placeId.equals(q.placeId())),
            org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void anonymous_tripItemId는_공통_AUTHENTICATION_REQUIRED_401이다() throws Exception {
    UUID itemId = UUID.fromString("50000000-0000-4000-8000-000000000005");
    when(service.forecast(
            org.mockito.ArgumentMatchers.argThat(q -> itemId.equals(q.tripItemId())),
            org.mockito.ArgumentMatchers.isNull()))
        .thenThrow(new WeatherForecastException("AUTHENTICATION_REQUIRED"));

    mvc.perform(
            get(PATH)
                .queryParam("tripItemId", itemId.toString())
                .queryParam("dateTime", "2026-08-03T15:00:00+09:00"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void invalid_bearer는_optional_endpoint에서도_INVALID_ACCESS_TOKEN_401이다() throws Exception {
    mvc.perform(
            get(PATH)
                .queryParam("tripItemId", "50000000-0000-4000-8000-000000000005")
                .queryParam("dateTime", "2026-08-03T15:00:00+09:00")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
        .andExpect(jsonPath("$.status").value(401));
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  @Test
  void foreign_owner의_tripItemId는_식별자를_반사하지_않는_404이다() throws Exception {
    UUID owner = UUID.fromString("20000000-0000-4000-8000-000000000002");
    UUID itemId = UUID.fromString("50000000-0000-4000-8000-000000000005");
    when(service.forecast(
            org.mockito.ArgumentMatchers.argThat(q -> itemId.equals(q.tripItemId())),
            org.mockito.ArgumentMatchers.eq(owner)))
        .thenThrow(new WeatherForecastException("WEATHER_REFERENCE_NOT_FOUND"));

    String body =
        mvc.perform(
                get(PATH)
                    .queryParam("tripItemId", itemId.toString())
                    .queryParam("dateTime", "2026-08-03T15:00:00+09:00")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(owner)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("WEATHER_REFERENCE_NOT_FOUND"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.lat").doesNotExist())
            .andExpect(jsonPath("$.lng").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(itemId.toString());
  }

  @Test
  void 좌표_입력과_잘못된_KST_정시는_400이다() throws Exception {
    mvc.perform(
            get(PATH)
                .queryParam("lng", "126.94")
                .queryParam("dateTime", "2026-08-03T15:00:00+09:00"))
        .andExpect(status().isBadRequest());
    mvc.perform(validRequest().queryParam("lat", "90")).andExpect(status().isBadRequest());
    mvc.perform(validRequest().queryParam("lng", "181")).andExpect(status().isBadRequest());
    mvc.perform(validRequest().queryParam("dateTime", "2026-08-03T06:00:00Z"))
        .andExpect(status().isBadRequest());
    mvc.perform(validRequest().queryParam("dateTime", "2026-08-03T15:00:01+09:00"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 구버전_좌표와_잘못된_시각은_거부한다() throws Exception {
    for (String lat : new String[] {" 33.458111", "33.458111 ", "0x1.0p0", "33.0d", "33e0"}) {
      mvc.perform(validRequest().queryParam("lat", lat)).andExpect(status().isBadRequest());
      mvc.perform(validRequest().queryParam("lng", lat)).andExpect(status().isBadRequest());
    }
    for (String dateTime :
        new String[] {
          "2026-08-03T15:00+09:00",
          "2026-08-03T15:00:00.000+09:00",
          "2026-08-03T15:00:00Z",
          "2026-08-03T15:00:00+09:00 "
        }) {
      mvc.perform(validRequest().queryParam("dateTime", dateTime))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void unknown이나_duplicate_query는_canonical_400으로_닫는다() throws Exception {
    mvc.perform(validRequest().queryParam("raw", "provider-payload"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WEATHER_SELECTOR"));
    mvc.perform(validRequest().queryParam("lat", "33.5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WEATHER_SELECTOR"));
  }

  @Test
  void typed_422와_503은_raw_provider_message없이_traceId를_반환한다() throws Exception {
    when(service.forecast(any(), any()))
        .thenThrow(new WeatherForecastException("WEATHER_LOCATION_NOT_SUPPORTED"));
    mvc.perform(validRequest())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("WEATHER_LOCATION_NOT_SUPPORTED"))
        .andExpect(
            jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
        .andExpect(jsonPath("$.providerMessage").doesNotExist());

    org.mockito.Mockito.reset(service);
    when(service.forecast(any(), any()))
        .thenThrow(new WeatherForecastException("WEATHER_FORECAST_UNAVAILABLE"));
    mvc.perform(validRequest())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("WEATHER_FORECAST_UNAVAILABLE"))
        .andExpect(jsonPath("$.rawPayload").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist());
  }

  @Test
  void 선택자_누락_복수_중복과_위치_파생값은_원문_반사없이_거부한다(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    mvc.perform(get(PATH).queryParam("dateTime", "2026-08-03T15:00:00+09:00"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WEATHER_SELECTOR"));
    for (String key :
        new String[] {
          "regionCode",
          "placeId",
          "tripItemId",
          "currentLocation",
          "gridHash",
          "nearestPlaceId",
          "lat",
          "lng"
        }) {
      String body =
          mvc.perform(validRequest().queryParam(key, "private-location-marker"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value("INVALID_WEATHER_SELECTOR"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      org.assertj.core.api.Assertions.assertThat(body).doesNotContain("private-location-marker");
      org.assertj.core.api.Assertions.assertThat(output.getAll())
          .doesNotContain("private-location-marker");
    }
  }

  @Test
  void 계획_조회는_검증된_JWT_sub만_service에_전달한다() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    mvc.perform(
            get(PATH)
                .queryParam("tripItemId", item.toString())
                .queryParam("dateTime", "2026-08-03T15:00:00+09:00")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(owner)))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(service)
        .forecast(
            org.mockito.ArgumentMatchers.argThat(q -> item.equals(q.tripItemId())),
            org.mockito.ArgumentMatchers.eq(owner));
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      validRequest() {
    return get(PATH)
        .queryParam("regionCode", "seogwipo-si")
        .queryParam("dateTime", "2026-08-03T15:00:00+09:00");
  }

  private static WeatherForecastResponse success() {
    return new WeatherForecastResponse(
        "2.0.0",
        new WeatherGridResponse(60, 37, "제주 동부"),
        "KMA",
        "VilageFcstInfoService_2.0",
        "ultra_short",
        LocalDate.parse("2026-08-03"),
        LocalTime.parse("13:30"),
        OffsetDateTime.parse("2026-08-03T13:30:00+09:00"),
        OffsetDateTime.parse("2026-08-03T15:00:00+09:00"),
        new BigDecimal("27.5"),
        null,
        new BigDecimal("0.0"),
        "none",
        null,
        70,
        new BigDecimal("2.1"),
        OffsetDateTime.parse("2026-08-03T14:10:00+09:00"),
        OffsetDateTime.parse("2026-08-03T14:20:00+09:00"),
        true,
        false);
  }

  private static String token(UUID userId) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(userId.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    token.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return token.serialize();
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
