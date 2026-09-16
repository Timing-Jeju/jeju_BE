package com.timingjeju.api.domain.accountdeletion.controller;

import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.accountdeletion.controller.docs.AccountDeletionApiDocs;
import com.timingjeju.api.domain.accountdeletion.dto.AccountDeletionAcceptedResponse;
import com.timingjeju.api.domain.accountdeletion.dto.AccountDeletionRequest;
import com.timingjeju.api.domain.accountdeletion.dto.AccountDeletionStatusResponse;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionException;
import com.timingjeju.api.domain.accountdeletion.service.AccountDeletionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "app.account-deletion", name = "enabled", havingValue = "true")
public final class AccountDeletionController implements AccountDeletionApiDocs {
  private final AccountDeletionService service;
  private final CurrentUserAccessor users;

  public AccountDeletionController(AccountDeletionService service, CurrentUserAccessor users) {
    this.service = service;
    this.users = users;
  }

  @Override
  @DeleteMapping("/api/v1/me")
  public ResponseEntity<AccountDeletionAcceptedResponse> request(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody AccountDeletionRequest request) {
    var receipt = service.request(users.getRequired(), idempotencyKey, request.getConfirmation());
    return ResponseEntity.accepted().body(AccountDeletionAcceptedResponse.from(receipt));
  }

  @Override
  @GetMapping("/api/v1/account-deletion-requests/{deletionRequestId}")
  public AccountDeletionStatusResponse status(
      @PathVariable String deletionRequestId,
      @RequestHeader("X-Deletion-Status-Token") String statusToken) {
    if (!deletionRequestId.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
      throw new AccountDeletionException("INVALID_PROFILE_LEGAL_REQUEST");
    }
    return AccountDeletionStatusResponse.from(service.status(deletionRequestId, statusToken));
  }
}
