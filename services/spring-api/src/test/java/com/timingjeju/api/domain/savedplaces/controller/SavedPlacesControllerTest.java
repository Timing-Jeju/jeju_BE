package com.timingjeju.api.domain.savedplaces.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.model.SavedPlace;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCreateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceHttpSnapshot;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceUpdateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesListResult;
import com.timingjeju.api.domain.savedplaces.service.SavedPlaceService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Tag("slice")
class SavedPlacesControllerTest {

  private static final UUID USER_ID = UUID.fromString("18000000-0000-0000-0000-000000000001");
  private static final UUID PLACE_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");

  @Test
  void GET은_canonical_saved_place와_nullable_recommendedStayMinutes를_반환한다() throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);
    when(service.list(eq(USER_ID), any()))
        .thenReturn(new SavedPlacesListResult(List.of(place(null)), 20, false, null));
    MockMvc mvc = mvc(service);

    mvc.perform(get("/api/v1/me/saved-places"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].placeId").value(PLACE_ID.toString()))
        .andExpect(jsonPath("$.items[0].recommendedStayMinutes").value((Object) null))
        .andExpect(jsonPath("$.page.size").value(20))
        .andExpect(jsonPath("$.page.hasNext").value(false))
        .andExpect(jsonPath("$.page.nextCursor").value((Object) null));
  }

  @Test
  void POST_첫_저장은_201과_Location_ETag_replayed_false를_반환한다() throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);
    when(service.create(eq(USER_ID), eq("fixture-saved-place-create-001"), any()))
        .thenReturn(createResult(false));
    MockMvc mvc = mvc(service);

    mvc.perform(
            post("/api/v1/me/saved-places")
                .header("Idempotency-Key", "fixture-saved-place-create-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"placeId":"20000000-0000-0000-0000-000000000003","memo":" 방문 ",
                     "tags":["동쪽","선택"],"priority":5,"targetDay":1}
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/me/saved-places/" + PLACE_ID))
        .andExpect(header().string("ETag", "\"saved-place-v1\""))
        .andExpect(header().string("Idempotency-Replayed", "false"))
        .andExpect(jsonPath("$.placeId").value(PLACE_ID.toString()));
  }

  @Test
  void POST_replay는_현재_DTO_serialization이_아닌_저장된_status_headers_body_bytes를_그대로_반환한다()
      throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);
    SavedPlaceHttpSnapshot snapshot =
        new SavedPlaceHttpSnapshot(
            201,
            "application/json",
            "/api/v1/me/saved-places/" + PLACE_ID,
            "\"immutable-etag\"",
            "{\"deployment\":\"original-bytes\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(service.create(eq(USER_ID), eq("immutable-replay"), any()))
        .thenReturn(new SavedPlaceCreateResult(place(60), snapshot.etag(), true, true, snapshot));

    mvc(service)
        .perform(
            post("/api/v1/me/saved-places")
                .header("Idempotency-Key", "immutable-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":\"" + PLACE_ID + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", snapshot.location()))
        .andExpect(header().string("ETag", "\"immutable-etag\""))
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(content().bytes(snapshot.body()));
  }

  @Test
  void PATCH는_If_Match와_presence_null_replace를_service에_전달하고_새_ETag를_반환한다() throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);
    when(service.patch(eq(USER_ID), eq(PLACE_ID), eq("\"saved-place-v1\""), any()))
        .thenReturn(new SavedPlaceUpdateResult(place(60), "\"saved-place-v2\""));

    mvc(service)
        .perform(
            patch("/api/v1/me/saved-places/{placeId}", PLACE_ID)
                .header("If-Match", "\"saved-place-v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":null,\"tags\":[\"동쪽\"],\"priority\":null,\"targetDay\":2}"))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"saved-place-v2\""))
        .andExpect(jsonPath("$.placeId").value(PLACE_ID.toString()));
  }

  @Test
  void DELETE는_owner와_placeId만_전달하고_204_body없음을_반환한다() throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);

    mvc(service)
        .perform(delete("/api/v1/me/saved-places/{placeId}", PLACE_ID))
        .andExpect(status().isNoContent())
        .andExpect(jsonPath("$").doesNotExist());
  }

  @Test
  void GET은_unknown과_duplicate_scalar_query를_INVALID_QUERY_PARAMETER로_거부한다() throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);
    when(service.list(eq(USER_ID), any()))
        .thenReturn(new SavedPlacesListResult(List.of(), 20, false, null));

    assertProblem(
        () -> mvc(service).perform(get("/api/v1/me/saved-places").queryParam("unknown", "x")),
        "INVALID_QUERY_PARAMETER");
    assertProblem(
        () -> mvc(service).perform(get("/api/v1/me/saved-places").queryParam("tag", "동쪽", "서쪽")),
        "INVALID_QUERY_PARAMETER");
  }

  @Test
  void POST_PATCH는_known_JSON_duplicate_key를_INVALID_REQUEST로_거부한다() throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);
    when(service.create(eq(USER_ID), any(), any())).thenReturn(createResult(false));
    when(service.patch(eq(USER_ID), eq(PLACE_ID), any(), any()))
        .thenReturn(new SavedPlaceUpdateResult(place(60), "\"saved-place-v2\""));

    assertProblem(
        () ->
            mvc(service)
                .perform(
                    post("/api/v1/me/saved-places")
                        .header("Idempotency-Key", "duplicate-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"placeId\":\"20000000-0000-0000-0000-000000000003\",\"memo\":\"a\",\"memo\":\"b\"}")),
        "INVALID_REQUEST");
    assertProblem(
        () ->
            mvc(service)
                .perform(
                    patch("/api/v1/me/saved-places/{placeId}", PLACE_ID)
                        .header("If-Match", "\"saved-place-v1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":1,\"priority\":2}")),
        "INVALID_REQUEST");
  }

  @Test
  void command_endpoint는_query와_noncanonical_path_body_UUID를_INVALID_REQUEST로_거부한다()
      throws Exception {
    SavedPlaceService service = mock(SavedPlaceService.class);

    assertProblem(
        () ->
            mvc(service)
                .perform(
                    post("/api/v1/me/saved-places")
                        .queryParam("x", "1")
                        .header("Idempotency-Key", "query-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":\"20000000-0000-0000-0000-000000000003\"}")),
        "INVALID_REQUEST");
    assertProblem(
        () ->
            mvc(service)
                .perform(
                    post("/api/v1/me/saved-places")
                        .header("Idempotency-Key", "uppercase-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":\"20000000-0000-0000-0000-00000000000A\"}")),
        "INVALID_REQUEST");
    assertProblem(
        () ->
            mvc(service)
                .perform(
                    patch("/api/v1/me/saved-places/20000000-0000-0000-0000-00000000000A")
                        .header("If-Match", "\"saved-place-v1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":null}")),
        "INVALID_REQUEST");
    assertProblem(
        () ->
            mvc(service)
                .perform(
                    patch("/api/v1/me/saved-places/{placeId}", PLACE_ID)
                        .queryParam("x", "1")
                        .header("If-Match", "\"saved-place-v1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":null}")),
        "INVALID_REQUEST");
    assertProblem(
        () ->
            mvc(service)
                .perform(
                    delete("/api/v1/me/saved-places/{placeId}", PLACE_ID).queryParam("x", "1")),
        "INVALID_REQUEST");
    assertProblem(
        () ->
            mvc(service)
                .perform(
                    delete("/api/v1/me/saved-places/{placeId}", PLACE_ID)
                        .header("Transfer-Encoding", "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
        "INVALID_REQUEST");
    assertProblem(
        () ->
            mvc(service)
                .perform(delete("/api/v1/me/saved-places/20000000-0000-0000-0000-00000000000A")),
        "INVALID_REQUEST");
  }

  private static void assertProblem(MvcCall call, String code) throws Exception {
    try {
      call.execute()
          .andExpect(
              result -> {
                if (result.getResolvedException()
                    instanceof
                    com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException problem) {
                  assertThat(problem.code()).isEqualTo(code);
                } else if (result.getResolvedException()
                    instanceof HttpMessageNotReadableException) {
                  assertThat(code).isEqualTo("INVALID_REQUEST");
                } else if (result.getResolvedException()
                    instanceof
                    org.springframework.web.method.annotation.HandlerMethodValidationException) {
                  assertThat(code).isEqualTo("INVALID_REQUEST");
                } else {
                  throw new AssertionError(
                      "SavedPlaceException expected", result.getResolvedException());
                }
              });
    } catch (jakarta.servlet.ServletException wrapped) {
      assertThat(wrapped.getCause()).isInstanceOf(SavedPlaceException.class);
      assertThat(((SavedPlaceException) wrapped.getCause()).code()).isEqualTo(code);
    }
  }

  private static SavedPlaceCreateResult createResult(boolean replayed) {
    var snapshot =
        new SavedPlaceHttpSnapshot(
            201,
            "application/json",
            "/api/v1/me/saved-places/" + PLACE_ID,
            "\"saved-place-v1\"",
            ("{\"placeId\":\"" + PLACE_ID + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new SavedPlaceCreateResult(place(60), snapshot.etag(), replayed, true, snapshot);
  }

  @FunctionalInterface
  private interface MvcCall {
    org.springframework.test.web.servlet.ResultActions execute() throws Exception;
  }

  private static MockMvc mvc(SavedPlaceService service) {
    CurrentUserAccessor users = mock(CurrentUserAccessor.class);
    when(users.getRequired())
        .thenReturn(new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null));
    JsonMapper mapper =
        JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    return MockMvcBuilders.standaloneSetup(new SavedPlacesController(service, users))
        .setMessageConverters(
            new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(mapper))
        .build();
  }

  private static SavedPlace place(Integer stay) {
    Instant timestamp = Instant.parse("2026-08-03T00:05:00Z");
    return new SavedPlace(
        PLACE_ID,
        "성산일출봉",
        "VE",
        "성산",
        "https://example.com/thumb.jpg",
        stay,
        "방문",
        List.of("동쪽", "선택"),
        5,
        1,
        timestamp,
        timestamp);
  }
}
