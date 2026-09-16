package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdkSupabaseAdminHttpTransportTest {
  private static final HttpRequest REQUEST =
      HttpRequest.newBuilder(URI.create("https://project.supabase.invalid/admin")).GET().build();

  @Test
  void completed_response를_status와_body로_변환한다() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<byte[]> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("four".getBytes(StandardCharsets.UTF_8));
    when(client.sendAsync(any(), anyByteArrayHandler()))
        .thenReturn(CompletableFuture.completedFuture(response));
    var transport = new JdkSupabaseAdminHttpTransport(client);

    SupabaseAdminHttpResponse accepted = transport.exchange(REQUEST, new byte[0], 4);
    assertThat(accepted.status()).isEqualTo(200);
    assertThat(accepted.body()).isEqualTo("four".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void IO와_interrupt는_secret_cause없이_retryable이고_interrupt_flag를_복구한다() throws Exception {
    HttpClient ioFailure = mock(HttpClient.class);
    when(ioFailure.sendAsync(any(), anyByteArrayHandler()))
        .thenReturn(CompletableFuture.failedFuture(new IOException("credential")));
    assertNetworkFailure(new JdkSupabaseAdminHttpTransport(ioFailure));

    HttpClient interrupted = mock(HttpClient.class);
    when(interrupted.sendAsync(any(), anyByteArrayHandler())).thenReturn(new CompletableFuture<>());
    try {
      Thread.currentThread().interrupt();
      assertNetworkFailure(new JdkSupabaseAdminHttpTransport(interrupted));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void deadline은_underlying_sendAsync_future를_cancel한다() {
    HttpClient client = mock(HttpClient.class);
    CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
    when(client.sendAsync(any(), anyByteArrayHandler())).thenReturn(pending);

    assertNetworkFailure(
        new JdkSupabaseAdminHttpTransport(client, java.time.Duration.ofMillis(20)));

    assertThat(pending).isCancelled();
  }

  private static void assertNetworkFailure(JdkSupabaseAdminHttpTransport transport) {
    assertThatThrownBy(() -> transport.exchange(REQUEST, new byte[0], 16))
        .isInstanceOfSatisfying(
            DeletionOperationException.class, failure -> assertThat(failure.isRetryable()).isTrue())
        .hasMessage("SUPABASE_NETWORK_FAILURE")
        .hasNoCause();
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse.BodyHandler<byte[]> anyByteArrayHandler() {
    return any(HttpResponse.BodyHandler.class);
  }
}
