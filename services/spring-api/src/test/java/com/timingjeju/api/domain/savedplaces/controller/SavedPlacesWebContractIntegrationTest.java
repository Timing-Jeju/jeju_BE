package com.timingjeju.api.domain.savedplaces.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.pagination.CursorContextMismatchException;
import com.timingjeju.api.application.pagination.CursorInvalidException;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCreateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceHttpSnapshot;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacePatchCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceUpdateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesListResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesQuery;
import com.timingjeju.api.domain.savedplaces.repository.SavedPlaceIdempotencyRetentionRepository;
import com.timingjeju.api.domain.savedplaces.repository.SavedPlaceRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("slice")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
@Import(SavedPlacesWebContractIntegrationTest.Fakes.class)
class SavedPlacesWebContractIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER = UUID.fromString("34300000-0000-0000-0000-000000000001");
  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @Test
  void strict_duplicate_JSON과_noncanonical_UUID는_endpoint에서_INVALID_REQUEST다() throws Exception {
    mvc.perform(
            post("/api/v1/me/saved-places")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "strict-duplicate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"placeId\":\"20000000-0000-0000-0000-000000000003\",\"memo\":\"a\",\"memo\":\"b\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mvc.perform(
            post("/api/v1/me/saved-places")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "uppercase-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":\"20000000-0000-0000-0000-00000000000A\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void canonical_Problem_fixture문구는_saved_places_endpoint가_독립적으로_소유한다() throws Exception {
    problem(
        get("/api/v1/me/saved-places").queryParam("size", "0"),
        400,
        "INVALID_QUERY_PARAMETER",
        "조회 조건이 올바르지 않습니다",
        "관심 장소 조회 조건을 확인해 주세요.");
    problem(
        get("/api/v1/me/saved-places").queryParam("cursor", "invalid"),
        400,
        "INVALID_CURSOR",
        "커서가 올바르지 않습니다",
        "처음부터 다시 조회해 주세요.");
    problem(
        get("/api/v1/me/saved-places").queryParam("cursor", "mismatch"),
        400,
        "CURSOR_CONTEXT_MISMATCH",
        "커서의 조회 조건이 현재 요청과 다릅니다",
        "변경한 조건으로 처음부터 다시 조회해 주세요.");
    problem(
        post("/api/v1/me/saved-places")
            .header("Idempotency-Key", "missing-place")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"placeId\":\"20000000-0000-0000-0000-000000000003\"}"),
        404,
        "PLACE_NOT_FOUND",
        "장소를 찾을 수 없습니다",
        "저장하려는 장소 정보가 없습니다.");
  }

  private void problem(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int status,
      String code,
      String title,
      String detail)
      throws Exception {
    mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.title").value(title))
        .andExpect(jsonPath("$.detail").value(detail));
  }

  private static String token() throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(USER.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    @Bean
    @Primary
    SavedPlaceIdempotencyRetentionRepository fakeSavedPlaceRetention() {
      return mock(SavedPlaceIdempotencyRetentionRepository.class);
    }

    @Bean
    @Primary
    SavedPlaceRepository fakeSavedPlaceRepository() {
      return new SavedPlaceRepository() {
        @Override
        public SavedPlacesListResult list(UUID owner, SavedPlacesQuery query) {
          if ("invalid".equals(query.cursor())) throw new CursorInvalidException();
          if ("mismatch".equals(query.cursor())) throw new CursorContextMismatchException();
          return new SavedPlacesListResult(java.util.List.of(), query.size(), false, null);
        }

        @Override
        public SavedPlaceCreateResult create(UUID owner, String key, SavedPlaceCommand command) {
          throw SavedPlaceException.of("PLACE_NOT_FOUND");
        }

        @Override
        public void completeSnapshot(UUID owner, String key, SavedPlaceHttpSnapshot snapshot) {}

        @Override
        public SavedPlaceUpdateResult patch(
            UUID owner, UUID placeId, String ifMatch, SavedPlacePatchCommand command) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(UUID owner, UUID placeId) {
          return false;
        }
      };
    }
  }
}
