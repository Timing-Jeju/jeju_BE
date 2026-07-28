package com.timingjeju.api.application.security;

public interface CurrentUserAccessor {

  CurrentUser getRequired();
}
