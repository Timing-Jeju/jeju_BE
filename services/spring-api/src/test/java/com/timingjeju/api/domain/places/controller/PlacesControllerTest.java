package com.timingjeju.api.domain.places.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.places.dto.response.PlaceCursorPage;
import com.timingjeju.api.domain.places.dto.response.PlacesListResponse;
import com.timingjeju.api.domain.places.service.PlaceListService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("slice")
class PlacesControllerTest {

  @Test
  void query와_savedOnly를_정규화해_service에_전달하고_닫힌_page_shape를_반환한다() throws Exception {
    PlaceListService service = mock(PlaceListService.class);
    CurrentUserAccessor users = mock(CurrentUserAccessor.class);
    when(users.getOptional()).thenReturn(Optional.empty());
    when(service.list(org.mockito.ArgumentMatchers.any(), eq(Optional.empty())))
        .thenReturn(new PlacesListResponse(List.of(), new PlaceCursorPage(100, false, null)));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlacesController(service, users)).build();

    mvc.perform(get("/api/v1/places").queryParam("query", " 성산 ").queryParam("size", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.page.size").value(100))
        .andExpect(jsonPath("$.page.hasNext").value(false))
        .andExpect(jsonPath("$.page.nextCursor").value((Object) null));

    verify(service)
        .list(
            argThat(query -> "성산".equals(query.query()) && query.size() == 100),
            eq(Optional.empty()));
  }
}
