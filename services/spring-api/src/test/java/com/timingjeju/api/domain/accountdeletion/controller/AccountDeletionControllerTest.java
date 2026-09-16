package com.timingjeju.api.domain.accountdeletion.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionReceipt;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatus;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatusView;
import com.timingjeju.api.domain.accountdeletion.service.AccountDeletionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("slice")
class AccountDeletionControllerTest {
  private static final UUID USER = UUID.fromString("61000000-0000-0000-0000-000000000001");
  private static final UUID SESSION = UUID.fromString("61000000-0000-0000-0000-000000000002");
  private static final String REQUEST = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
  private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

  @Test
  void DELETE_me는_필수_계약과_202_queued를_반환한다() throws Exception {
    AccountDeletionService service = mock(AccountDeletionService.class);
    CurrentUser current = new CurrentUser(USER, AuthenticatedRole.AUTHENTICATED, SESSION);
    when(service.request(current, "printable key:1", "DELETE_MY_ACCOUNT"))
        .thenReturn(
            new AccountDeletionReceipt(
                REQUEST,
                AccountDeletionStatus.QUEUED,
                TOKEN,
                NOW,
                NOW.plusSeconds(3600),
                "/api/v1/account-deletion-requests/" + REQUEST));

    mvc(service, current)
        .perform(
            delete("/api/v1/me")
                .header("Idempotency-Key", "printable key:1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"DELETE_MY_ACCOUNT\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.deletionRequestId").value(REQUEST))
        .andExpect(jsonPath("$.status").value("queued"))
        .andExpect(jsonPath("$.statusToken").value(TOKEN))
        .andExpect(jsonPath("$.pollUrl").value("/api/v1/account-deletion-requests/" + REQUEST));
  }

  @Test
  void GET_status는_opaque_header로_cancelled를_포함한_상태만_반환한다() throws Exception {
    AccountDeletionService service = mock(AccountDeletionService.class);
    when(service.status(REQUEST, TOKEN))
        .thenReturn(
            new AccountDeletionStatusView(
                REQUEST, AccountDeletionStatus.CANCELLED, "cancelled", null, NOW.plusSeconds(5)));

    mvc(service, new CurrentUser(USER, AuthenticatedRole.AUTHENTICATED, SESSION))
        .perform(
            get("/api/v1/account-deletion-requests/{id}", REQUEST)
                .header("X-Deletion-Status-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("cancelled"))
        .andExpect(jsonPath("$.currentStep").value("cancelled"))
        .andExpect(jsonPath("$.userId").doesNotExist())
        .andExpect(jsonPath("$.statusToken").doesNotExist());
  }

  private static MockMvc mvc(AccountDeletionService service, CurrentUser current) {
    CurrentUserAccessor users = mock(CurrentUserAccessor.class);
    when(users.getRequired()).thenReturn(current);
    return MockMvcBuilders.standaloneSetup(new AccountDeletionController(service, users)).build();
  }
}
