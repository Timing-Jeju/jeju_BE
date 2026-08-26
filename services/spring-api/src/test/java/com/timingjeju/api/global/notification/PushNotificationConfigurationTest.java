package com.timingjeju.api.global.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PushNotificationConfigurationTest {

  private final PushNotificationConfiguration configuration = new PushNotificationConfiguration();

  @Test
  void token암호화키는_base64_32byte가_아니면_시작을_거부한다() {
    for (String invalid : new String[] {"", "not-base64", "c2hvcnQ="}) {
      assertThatThrownBy(() -> configuration.registrationTokenProtector(invalid))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("푸시 token 암호화 키 구성이 유효하지 않습니다.")
          .hasNoCause();
    }
  }
}
