package com.timingjeju.api.application.idempotency;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record IdempotencyHeader(String name, String value) {

  private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final Set<String> FORBIDDEN =
      Set.of("authorization", "proxy-authorization", "set-cookie", "cookie");

  public IdempotencyHeader {
    if (name == null || !TOKEN.matcher(name).matches()) {
      throw new IllegalArgumentException("response header name이 올바르지 않습니다.");
    }
    if (FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("인증 정보가 포함된 response header는 저장할 수 없습니다.");
    }
    if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("response header value가 올바르지 않습니다.");
    }
  }
}
