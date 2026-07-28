package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.RemoteKeySourceException;
import java.io.IOException;
import java.text.ParseException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

@Tag("unit")
class RemoteJwksFailureClassifierTest {

  @Test
  void 원격_가용성_실패만_알려진_인증실패로_분류한다() {
    assertThat(
            RemoteJwksFailureClassifier.isAvailabilityFailure(
                new IllegalStateException(
                    new RemoteKeySourceException("safe", new IOException("safe")))))
        .isTrue();
    assertThat(
            RemoteJwksFailureClassifier.isAvailabilityFailure(
                new RemoteKeySourceException(
                    "safe",
                    HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "", null, null, null))))
        .isTrue();
    assertThat(
            RemoteJwksFailureClassifier.isAvailabilityFailure(
                new RemoteKeySourceException("safe", new ParseException("safe", 0))))
        .isFalse();
    assertThat(
            RemoteJwksFailureClassifier.isAvailabilityFailure(
                new RemoteKeySourceException("safe", new IllegalStateException("safe"))))
        .isFalse();
  }
}
