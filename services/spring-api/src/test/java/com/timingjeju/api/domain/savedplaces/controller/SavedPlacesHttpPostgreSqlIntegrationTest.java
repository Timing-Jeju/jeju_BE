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
  private static final UUID SECOND_PLACE = UUID.fromString("34100000-0000-0000-0000-000000000012");
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private tools.jackson.databind.ObjectMapper mapper;

  @Autowired
  private com.timingjeju.api.domain.savedplaces.repository.SavedPlaceRepository repository;

  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

  protected int expectedPostgresMajor() {
    return 16;
  }

  @Test
  void 지정된_PostgreSQL_버전에서_실제로_검증한다() {
    assertThat(
            Integer.parseInt(
                    jdbc.queryForObject(
                        "select current_setting('server_version_num')", String.class))
                / 10000)
        .isEqualTo(expectedPostgresMajor());
  }

  @BeforeEach
  void setUp() {
    jdbc.update("delete from auth.users where id=?", USER);
    jdbc.update("delete from public.tour_places where id in (?,?)", PLACE, SECOND_PLACE);
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
  void 여러_페이지의_각_항목_ETag는_해당_소유_row의_실제_버전과_일치한다() throws Exception {
    jdbc.update(
        """
        insert into public.tour_places(id,content_id,name,normalized_name,category,region_code,
          region_label,location,recommended_stay_minutes,source_provider,source_service)
        select ?,?,'두 번째 장소','두 번째 장소',category,region_code,region_label,location,
          recommended_stay_minutes,source_provider,source_service from public.tour_places where id=?
        """,
        SECOND_PLACE,
        "content-" + SECOND_PLACE,
        PLACE);
    for (UUID id : java.util.List.of(PLACE, SECOND_PLACE)) {
      mvc.perform(
              post("/api/v1/me/saved-places")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                  .header("Idempotency-Key", "issue238-page-" + id)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"placeId\":\"" + id + "\"}"))
          .andExpect(status().isCreated());
    }
    String cursor = null;
    var ids = new java.util.HashSet<UUID>();
    for (int page = 0; page < 2; page++) {
      var request =
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                  "/api/v1/me/saved-places")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
              .param("size", "1");
      if (cursor != null) request.param("cursor", cursor);
      var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
      var body = mapper.readTree(response.getContentAsByteArray());
      var item = body.path("items").get(0);
      UUID id = UUID.fromString(item.path("placeId").asText());
      assertThat(ids.add(id)).isTrue();
      Instant updated =
          jdbc.queryForObject(
                  "select updated_at from public.saved_places where user_id=? and place_id=?",
                  java.sql.Timestamp.class,
                  USER,
                  id)
              .toInstant();
      assertThat(item.path("etag").asText())
          .isEqualTo(
              com.timingjeju.api.domain.savedplaces.model.SavedPlaceEtag.strong(id, updated));
      cursor =
          body.path("page").path("nextCursor").isNull()
              ? null
              : body.path("page").path("nextCursor").asText();
      assertThat(body.path("page").path("hasNext").asBoolean()).isEqualTo(page == 0);
    }
    assertThat(ids).containsExactlyInAnyOrder(PLACE, SECOND_PLACE);
  }

  @Test
  void 새_세션의_목록_ETag로_수정하고_이전_버전_충돌은_최신값을_보존한다() throws Exception {
    var created =
        mvc.perform(
                post("/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .header("Idempotency-Key", "issue238-create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"placeId\":\"" + PLACE + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    assertThat(mapper.readTree(created.getContentAsByteArray()).path("etag").asText())
        .isEqualTo(created.getHeader("ETag"));
    var list =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String original =
        mapper.readTree(list.getContentAsByteArray()).path("items").get(0).path("etag").asText();
    assertThat(original).isEqualTo(created.getHeader("ETag"));
    var updated =
        mvc.perform(
                patch("/api/v1/me/saved-places/{placeId}", PLACE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .header("If-Match", original)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"memo\":\"다른 세션 저장\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(mapper.readTree(updated.getContentAsByteArray()).path("etag").asText())
        .isEqualTo(updated.getHeader("ETag"))
        .isNotEqualTo(original);
    mvc.perform(
            patch("/api/v1/me/saved-places/{placeId}", PLACE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("If-Match", original)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"오래된 화면\"}"))
        .andExpect(status().isConflict());
    var latest =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var item = mapper.readTree(latest.getContentAsByteArray()).path("items").get(0);
    assertThat(item.path("etag").asText()).isEqualTo(updated.getHeader("ETag"));
    assertThat(item.path("memo").asText()).isEqualTo("다른 세션 저장");
    mvc.perform(
            patch("/api/v1/me/saved-places/{placeId}", PLACE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("If-Match", item.path("etag").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"재조회 후 저장\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void 배포_전_ETag없는_POST_body는_재시도에서_보존하고_새_목록은_ETag를_제공한다() throws Exception {
    String key = "issue238-legacy";
    var original =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(
                status -> {
                  var created =
                      repository.create(
                          USER,
                          key,
                          com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand.create(
                              PLACE, null, null, null, null));
                  var legacy =
                      (tools.jackson.databind.node.ObjectNode)
                          mapper.valueToTree(
                              com.timingjeju.api.domain.savedplaces.dto.SavedPlaceResponse.from(
                                  created.place()));
                  legacy.remove("etag");
                  var snapshot =
                      new com.timingjeju.api.domain.savedplaces.model.SavedPlaceHttpSnapshot(
                          201,
                          "application/json",
                          "/api/v1/me/saved-places/" + PLACE,
                          created.etag(),
                          mapper.writeValueAsBytes(legacy));
                  repository.completeSnapshot(USER, key, snapshot);
                  return snapshot;
                });
    var expiry =
        jdbc.queryForObject(
            "select expires_at from public.saved_place_idempotency where owner_sub=? and idempotency_key=?",
            java.sql.Timestamp.class,
            USER,
            key);
    var replay =
        mvc.perform(
                post("/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"placeId\":\"" + PLACE + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    assertThat(replay.getContentAsByteArray()).isEqualTo(original.body());
    assertThat(replay.getHeader("ETag")).isEqualTo(original.etag());
    assertThat(replay.getHeader("Location")).isEqualTo(original.location());
    assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
    assertThat(
            jdbc.queryForObject(
                "select expires_at from public.saved_place_idempotency where owner_sub=? and idempotency_key=?",
                java.sql.Timestamp.class,
                USER,
                key))
        .isEqualTo(expiry);
    var latest =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(
            mapper
                .readTree(latest.getContentAsByteArray())
                .path("items")
                .get(0)
                .path("etag")
                .asText())
        .isEqualTo(original.etag());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.saved_places where user_id=?", Long.class, USER))
        .isEqualTo(1L);
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

  @Test
  void same_key의_memo_null과_literal_null은_409다() throws Exception {
    String prefix =
        "{\"placeId\":\"34100000-0000-0000-0000-000000000011\",\"tags\":[\"동쪽\"],\"priority\":1,\"memo\":";
    mvc.perform(
            post("/api/v1/me/saved-places")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "memo-null-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .content(prefix + "null}"))
        .andExpect(status().isCreated());

    var conflict =
        mvc.perform(
                post("/api/v1/me/saved-places")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .header("Idempotency-Key", "memo-null-conflict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(prefix + "\"null\"}"))
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(conflict.getResponse().getContentAsString())
        .contains("\"code\":\"IDEMPOTENCY_PAYLOAD_CONFLICT\"");
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
