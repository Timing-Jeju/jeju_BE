package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OpsJwtDecoderFactoryTest {

  @Test
  void 운영_JWT는_고정_audience와_HTTPS_issuer_JWKS만_허용한다() {
    OpsJwtDecoderFactory.validate(properties("https", "timing-jeju-ops", Duration.ofSeconds(30)));

    assertThatThrownBy(
            () ->
                OpsJwtDecoderFactory.validate(properties("http", "timing-jeju-ops", Duration.ZERO)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                OpsJwtDecoderFactory.validate(properties("https", "authenticated", Duration.ZERO)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void clock_skew는_0초부터_60초까지만_허용한다() {
    OpsJwtDecoderFactory.validate(properties("https", "timing-jeju-ops", Duration.ZERO));
    OpsJwtDecoderFactory.validate(properties("https", "timing-jeju-ops", Duration.ofSeconds(60)));

    assertThatThrownBy(
            () ->
                OpsJwtDecoderFactory.validate(
                    properties("https", "timing-jeju-ops", Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                OpsJwtDecoderFactory.validate(
                    properties("https", "timing-jeju-ops", Duration.ofSeconds(61))))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ExternalDataHealthOperatorProperties properties(
      String scheme, String audience, Duration clockSkew) {
    return new ExternalDataHealthOperatorProperties(
        true,
        URI.create(scheme + "://ops.timing-jeju.invalid"),
        audience,
        URI.create(scheme + "://ops.timing-jeju.invalid/.well-known/jwks.json"),
        clockSkew);
  }
}
