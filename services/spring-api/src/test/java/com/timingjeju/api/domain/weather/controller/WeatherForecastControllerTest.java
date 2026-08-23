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
    when(service.forecast(any())).thenReturn(success());
  }

  @Test
  void anonymous와_valid_optional_JWT는_동일한_닫힌_200_projection을_받는다() throws Exception {
    mvc.perform(validRequest())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractVersion").value("1.0.0"))
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
  void invalid_bearer는_optional_endpoint에서도_INVALID_ACCESS_TOKEN_401이다() throws Exception {
    mvc.perform(validRequest().header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
  }

  @Test
  void WGS84_범위와_KST_정시_validation은_400이다() throws Exception {
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
  void coordinate와_dateTime은_canonical_ASCII_lexeme만_허용한다() throws Exception {
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
        .andExpect(jsonPath("$.code").value("INVALID_WEATHER_FORECAST_QUERY"));
    mvc.perform(validRequest().queryParam("lat", "33.5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WEATHER_FORECAST_QUERY"));
  }

  @Test
  void typed_422와_503은_raw_provider_message없이_traceId를_반환한다() throws Exception {
    when(service.forecast(any()))
        .thenThrow(new WeatherForecastException("WEATHER_LOCATION_NOT_SUPPORTED"));
    mvc.perform(validRequest())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("WEATHER_LOCATION_NOT_SUPPORTED"))
        .andExpect(
            jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
        .andExpect(jsonPath("$.providerMessage").doesNotExist());

    org.mockito.Mockito.reset(service);
    when(service.forecast(any()))
        .thenThrow(new WeatherForecastException("WEATHER_FORECAST_UNAVAILABLE"));
    mvc.perform(validRequest())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("WEATHER_FORECAST_UNAVAILABLE"))
        .andExpect(jsonPath("$.rawPayload").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      validRequest() {
    return get(PATH)
        .queryParam("lat", "33.458111")
        .queryParam("lng", "126.941516")
        .queryParam("dateTime", "2026-08-03T15:00:00+09:00");
  }

  private static WeatherForecastResponse success() {
    return new WeatherForecastResponse(
        "1.0.0",
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
