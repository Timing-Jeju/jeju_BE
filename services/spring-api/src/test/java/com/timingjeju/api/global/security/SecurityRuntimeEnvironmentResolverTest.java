package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class SecurityRuntimeEnvironmentResolverTest {

  @Test
  void profile이_없거나_이름만_local을_포함하면_운영_정책을_적용한다() {
    assertThat(SecurityRuntimeEnvironmentResolver.resolve(new MockEnvironment()))
        .isEqualTo(SecurityRuntimeEnvironment.PRODUCTION);
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                new MockEnvironment().withProperty("spring.profiles.active", "local-preview")))
        .isEqualTo(SecurityRuntimeEnvironment.PRODUCTION);
  }

  @Test
  void 정확한_local_profile만_로컬_정책을_적용한다() {
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                new MockEnvironment().withProperty("spring.profiles.active", "local")))
        .isEqualTo(SecurityRuntimeEnvironment.LOCAL);
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                new MockEnvironment().withProperty("spring.profiles.active", "local-hs256")))
        .isEqualTo(SecurityRuntimeEnvironment.LOCAL);
  }

  @Test
  void local과_운영_profile을_동시에_활성화하면_시작을_거부한다() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.profiles.active", "local,production");

    assertThatThrownBy(() -> SecurityRuntimeEnvironmentResolver.resolve(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("동시에");
  }
}
