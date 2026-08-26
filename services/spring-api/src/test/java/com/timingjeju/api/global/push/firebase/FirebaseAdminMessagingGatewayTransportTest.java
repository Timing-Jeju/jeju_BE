package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FirebaseAdminMessagingGatewayTransportTest {

  @Test
  void DNS_connect_TLS_handshake처럼_body_미전송이_증명된_원인만_pre_connect다() {
    for (Throwable failure :
        new Throwable[] {
          new UnknownHostException("host"),
          new ConnectException("connect"),
          new SSLHandshakeException("tls")
        }) {
      assertThat(FirebaseAdminMessagingGateway.classifyTransportFailure(wrapped(failure)).kind())
          .as(failure.getClass().getSimpleName())
          .isEqualTo(FirebaseFailureKind.PROVEN_PRE_CONNECT);
    }
  }

  @Test
  void timeout_reset_EOF와_원인불명은_post_write_ambiguity로_fail_closed한다() {
    for (Throwable failure :
        new Throwable[] {
          new SocketTimeoutException("read"),
          new SocketException("connection reset"),
          new EOFException("unexpected eof"),
          new IllegalStateException("unknown")
        }) {
      assertThat(FirebaseAdminMessagingGateway.classifyTransportFailure(wrapped(failure)).kind())
          .as(failure.getClass().getSimpleName())
          .isEqualTo(FirebaseFailureKind.POST_WRITE_AMBIGUOUS);
    }
  }

  private static RuntimeException wrapped(Throwable cause) {
    return new RuntimeException(new IllegalStateException(cause));
  }
}
