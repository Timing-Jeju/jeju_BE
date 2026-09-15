package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.*;
import com.timingjeju.api.application.trip.*;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class TripPlannerConditionsControllerTest {
  @Test
  void HTTP_입력실패도_기존_여행_Problem_응답으로_매핑한다() throws Exception {
    var writer = mock(com.timingjeju.api.global.error.ProblemResponseWriter.class);
    when(writer.write(any(), any(), anyString()))
        .thenAnswer(
            call -> {
              call.<jakarta.servlet.http.HttpServletResponse>getArgument(1).setStatus(400);
              return true;
            });
    var controller =
        new TripPlannerConditionsController(
            mock(com.timingjeju.api.application.trip.service.TripPlannerConditionsService.class),
            () -> Optional.empty(),
            mock(IdempotencyUseCase.class),
            JsonMapper.builder().build());
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new TripProblemExceptionHandler(writer, mock(TripPreferencesProblemWriter.class)))
            .build();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/trips/53000000-0000-0000-0000-000000000010/planner-conditions")
                .contentType("application/json")
                .content("{\"dayAnchors\":[],\"styleCodes\":[]}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    verify(writer).write(any(), any(), eq("INVALID_REQUEST"));
  }

  @Test
  void 필수_ETag와_멱등_범위로_현재사용자_여행만_저장한다() {
    UUID id = UUID.fromString("53000000-0000-0000-0000-000000000010");
    UUID owner = UUID.fromString("53000000-0000-0000-0000-000000000011");
    var store = mock(TripPlannerConditionsStore.class);
    when(store.replace(eq(owner), eq(id), eq(1L), any(), any()))
        .thenReturn(
            new TripAggregateMutationCommit<>(
                TripPlannerConditions.empty(),
                2,
                "draft",
                null,
                "none",
                false,
                TripEntityTag.strong(id, 2)));
    var receipts = mock(IdempotencyUseCase.class);
    when(receipts.execute(any(), any()))
        .thenAnswer(
            call -> {
              IdempotencyRequest request = call.getArgument(0);
              assertThat(request.normalizedPath())
                  .isEqualTo("/api/v1/trips/" + id + "/planner-conditions");
              assertThat(request.ownerSub()).isEqualTo(owner);
              return call.<IdempotencyOperation>getArgument(1).execute();
            });
    CurrentUserAccessor users =
        () -> Optional.of(new CurrentUser(owner, AuthenticatedRole.AUTHENTICATED, null));
    var controller =
        new TripPlannerConditionsController(
            new com.timingjeju.api.application.trip.service.TripPlannerConditionsService(
                store, Clock.systemUTC()),
            users,
            receipts,
            JsonMapper.builder().build());
    var request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(
        "{\"dayAnchors\":[],\"styleCodes\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("If-Match", TripEntityTag.strong(id, 1));
    request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
    var response = controller.replace(id.toString(), request);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getETag()).isEqualTo(TripEntityTag.strong(id, 2));
    verify(store).replace(eq(owner), eq(id), eq(1L), any(), any());
    request.removeHeader("If-Match");
    assertThatThrownBy(() -> controller.replace(id.toString(), request))
        .isInstanceOf(TripException.class);
    verifyNoMoreInteractions(store);
  }
}
