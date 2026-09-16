package com.timingjeju.api.domain.accountdeletion.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionException;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class AccountDeletionRequest {
  @Schema(example = "DELETE_MY_ACCOUNT", requiredMode = Schema.RequiredMode.REQUIRED)
  private String confirmation;

  public String getConfirmation() {
    return confirmation;
  }

  public void setConfirmation(String confirmation) {
    this.confirmation = confirmation;
  }

  @JsonAnySetter
  void unknown(String name, Object value) {
    throw new AccountDeletionException("INVALID_PROFILE_LEGAL_REQUEST");
  }
}
