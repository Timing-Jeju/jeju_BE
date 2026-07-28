package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class SecurityRuntimeEnvironmentResolverTest {

  @Test
  void profile이_없거나_보안과_무관한_단일_profile이면_운영_정책을_적용한다() {
    assertThat(SecurityRuntimeEnvironmentResolver.resolve(new MockEnvironment()).environment())
        .isEqualTo(SecurityRuntimeEnvironment.PRODUCTION);
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                    new MockEnvironment().withProperty("spring.profiles.active", "staging"))
                .environment())
        .isEqualTo(SecurityRuntimeEnvironment.PRODUCTION);
  }

  @Test
  void 정확한_local_profile만_로컬_정책을_적용한다() {
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                    new MockEnvironment().withProperty("spring.profiles.active", "local"))
                .environment())
        .isEqualTo(SecurityRuntimeEnvironment.LOCAL);
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                    new MockEnvironment().withProperty("spring.profiles.active", "local-hs256"))
                .environment())
        .isEqualTo(SecurityRuntimeEnvironment.LOCAL);
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                    new MockEnvironment().withProperty("spring.profiles.active", "local"))
                .allowedDecoderMode())
        .isEqualTo(JwtDecoderMode.JWKS);
    assertThat(
            SecurityRuntimeEnvironmentResolver.resolve(
                    new MockEnvironment().withProperty("spring.profiles.active", "local-hs256"))
                .allowedDecoderMode())
        .isEqualTo(JwtDecoderMode.HS256);
  }

  @Test
  void local과_운영_profile을_동시에_활성화하면_시작을_거부한다() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.profiles.active", "local,production");

    assertThatThrownBy(() -> SecurityRuntimeEnvironmentResolver.resolve(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("동시에");
  }

  @Test
  void local_보안_profile은_다른_profile과_조합할_수_없다() {
    for (String profiles :
        new String[] {
          "local,staging",
          "local,test",
          "local,prod",
          "local,production",
          "local,local-hs256",
          "local-hs256,staging",
          "local-hs256,test",
          "local-hs256,prod",
          "local-hs256,production"
        }) {
      MockEnvironment environment =
          new MockEnvironment().withProperty("spring.profiles.active", profiles);

      assertThatThrownBy(() -> SecurityRuntimeEnvironmentResolver.resolve(environment))
          .as(profiles)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void local_유사_대소문자_공백_profile은_명확히_거부한다() {
    for (String profile :
        new String[] {"local-preview", "LOCAL", "Local", "local-HS256", " local", "local "}) {
      MockEnvironment environment =
          new MockEnvironment().withProperty("spring.profiles.active", profile);

      assertThatThrownBy(() -> SecurityRuntimeEnvironmentResolver.resolve(environment))
          .as(profile)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void Spring_test_profile은_로컬_권한을_얻지_않고_운영_JWKS_정책을_적용한다() {
    SecurityRuntimePolicy policy =
        SecurityRuntimeEnvironmentResolver.resolve(
            new MockEnvironment().withProperty("spring.profiles.active", "test"));

    assertThat(policy.environment()).isEqualTo(SecurityRuntimeEnvironment.PRODUCTION);
    assertThat(policy.allowedDecoderMode()).isEqualTo(JwtDecoderMode.JWKS);
  }
}
