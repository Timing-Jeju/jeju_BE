package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdkSupabaseAdminHttpTransportTest {
  private static final HttpRequest REQUEST =
      HttpRequest.newBuilder(URI.create("https://project.supabase.invalid/admin")).GET().build();

  @Test
  void response_body를_상한까지_읽고_초과응답은_retryable로_닫는다() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn(
            new ByteArrayInputStream("four".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayInputStream("four".getBytes(StandardCharsets.UTF_8)));
    when(client.send(any(), anyInputStreamHandler())).thenReturn(response);
    var transport = new JdkSupabaseAdminHttpTransport(client);

    SupabaseAdminHttpResponse accepted = transport.exchange(REQUEST, new byte[0], 4);
    assertThat(accepted.status()).isEqualTo(200);
    assertThat(accepted.body()).isEqualTo("four".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> transport.exchange(REQUEST, new byte[0], 3))
        .isInstanceOfSatisfying(
            DeletionOperationException.class, failure -> assertThat(failure.isRetryable()).isTrue())
        .hasMessage("SUPABASE_RESPONSE_TOO_LARGE");
  }

  @Test
  void IO와_interrupt는_secret_cause없이_retryable이고_interrupt_flag를_복구한다() throws Exception {
    HttpClient ioFailure = mock(HttpClient.class);
    when(ioFailure.send(any(), anyInputStreamHandler())).thenThrow(new IOException("credential"));
    assertNetworkFailure(new JdkSupabaseAdminHttpTransport(ioFailure));

    HttpClient interrupted = mock(HttpClient.class);
    when(interrupted.send(any(), anyInputStreamHandler())).thenThrow(new InterruptedException());
    try {
      assertNetworkFailure(new JdkSupabaseAdminHttpTransport(interrupted));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static void assertNetworkFailure(JdkSupabaseAdminHttpTransport transport) {
    assertThatThrownBy(() -> transport.exchange(REQUEST, new byte[0], 16))
        .isInstanceOfSatisfying(
            DeletionOperationException.class, failure -> assertThat(failure.isRetryable()).isTrue())
        .hasMessage("SUPABASE_NETWORK_FAILURE")
        .hasNoCause();
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse.BodyHandler<java.io.InputStream> anyInputStreamHandler() {
    return any(HttpResponse.BodyHandler.class);
  }
}
