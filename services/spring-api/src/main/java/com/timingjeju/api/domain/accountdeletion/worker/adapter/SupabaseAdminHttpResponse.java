package com.timingjeju.api.domain.accountdeletion.worker.adapter;

public record SupabaseAdminHttpResponse(int status, byte[] body) {
  public SupabaseAdminHttpResponse {
    body = body == null ? new byte[0] : body.clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}
