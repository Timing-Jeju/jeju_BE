package com.timingjeju.api.domain.savedplaces.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
@Import(PostgreSqlTestcontainersConfiguration.class)
@Tag("integration")
class SavedPlacesHttpPostgreSqlIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = "test-" + "only-hs256-secret-with-at-least-32-bytes";
  private static final UUID USER = UUID.fromString("34100000-0000-0000-0000-000000000001");
  private static final UUID PLACE = UUID.fromString("34100000-0000-0000-0000-000000000011");
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from auth.users where id=?", USER);
    jdbc.update("delete from public.tour_places where id=?", PLACE);
    jdbc.update("insert into auth.users(id,email) values (?,?)", USER, USER + "@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", USER, USER + "@example.test");
    jdbc.update(
        """
        insert into public.tour_places(id,content_id,name,normalized_name,category,region_code,
          region_label,location,recommended_stay_minutes,source_provider,source_service)
        values (?,?, '성산일출봉','성산일출봉','VE','seongsan','성산',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,60,'fixture','saved-http-test')
        """,
        PLACE,
        "content-" + PLACE);
  }

  @Test
  void POST_snapshot_replay는_PATCH_DELETE후에도_original_status_headers_body를_exact복원한다()
      throws Exception {
    String body =
        "{\"placeId\":\"34100000-0000-0000-0000-000000000011\",\"memo\":\"원본\",\"tags\":[\"동쪽\"],\"priority\":5}";
    var first =
        mvc.perform(
                post("/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .header("Idempotency-Key", "http-snapshot-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String etag = first.getResponse().getHeader("ETag");
    String location = first.getResponse().getHeader("Location");
    String contentType = first.getResponse().getContentType();
    byte[] originalBody = first.getResponse().getContentAsByteArray();
    assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("false");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.saved_place_idempotency set response_body=? where owner_sub=? and idempotency_key=?",
                    "mutated".getBytes(StandardCharsets.UTF_8),
                    USER,
                    "http-snapshot-key"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    mvc.perform(
            patch("/api/v1/me/saved-places/{placeId}", PLACE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"변경\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            delete("/api/v1/me/saved-places/{placeId}", PLACE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
        .andExpect(status().isNoContent());
    jdbc.update("delete from public.tour_places where id=?", PLACE);

    var replay =
        mvc.perform(
                post("/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .header("Idempotency-Key", "http-snapshot-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(replay.getResponse().getStatus()).isEqualTo(first.getResponse().getStatus());
    assertThat(replay.getResponse().getContentType()).isEqualTo(contentType);
    assertThat(replay.getResponse().getHeader("Location")).isEqualTo(location);
    assertThat(replay.getResponse().getHeader("ETag")).isEqualTo(etag);
    assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.getResponse().getContentAsByteArray()).isEqualTo(originalBody);
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
}
