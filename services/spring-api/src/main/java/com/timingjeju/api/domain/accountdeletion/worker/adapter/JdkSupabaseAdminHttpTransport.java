package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class JdkSupabaseAdminHttpTransport implements SupabaseAdminHttpTransport {
  private static final long CHECKPOINT_INTERVAL_NANOS = Duration.ofMillis(100).toNanos();
  private final HttpClient client;
  private final Duration responseDeadline;

  public JdkSupabaseAdminHttpTransport(SupabaseAdminSettings settings) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(settings.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        settings.readTimeout());
  }

  JdkSupabaseAdminHttpTransport(HttpClient client) {
    this(client, Duration.ofSeconds(30));
  }

  JdkSupabaseAdminHttpTransport(HttpClient client, Duration responseDeadline) {
    this.client = java.util.Objects.requireNonNull(client);
    this.responseDeadline = java.util.Objects.requireNonNull(responseDeadline);
  }

  @Override
  public SupabaseAdminHttpResponse exchange(
      HttpRequest request, byte[] requestBody, int maximumBodyBytes) {
    return exchange(request, requestBody, maximumBodyBytes, () -> {});
  }

  @Override
  public SupabaseAdminHttpResponse exchange(
      HttpRequest request, byte[] requestBody, int maximumBodyBytes, Runnable inFlightCheckpoint) {
    Duration deadline = request.timeout().orElse(responseDeadline);
    long started = System.nanoTime();
    CompletableFuture<HttpResponse<byte[]>> exchange =
        client.sendAsync(request, ignored -> new BoundedBodySubscriber(maximumBodyBytes));
    try {
      while (true) {
        long remaining = Math.subtractExact(deadline.toNanos(), System.nanoTime() - started);
        if (remaining <= 0) {
          exchange.cancel(true);
          throw networkFailure();
        }
        try {
          HttpResponse<byte[]> response =
              exchange.get(Math.min(remaining, CHECKPOINT_INTERVAL_NANOS), TimeUnit.NANOSECONDS);
          return new SupabaseAdminHttpResponse(response.statusCode(), response.body());
        } catch (TimeoutException checkpointDue) {
          if (System.nanoTime() - started >= deadline.toNanos()) {
            exchange.cancel(true);
            throw networkFailure();
          }
          java.util.Objects.requireNonNull(inFlightCheckpoint).run();
        }
      }
    } catch (DeletionOperationException failure) {
      exchange.cancel(true);
      throw failure;
    } catch (ExecutionException failure) {
      if (hasCause(failure, BodyTooLarge.class)) {
        throw DeletionOperationException.retryable("SUPABASE_RESPONSE_TOO_LARGE");
      }
      throw networkFailure();
    } catch (InterruptedException failure) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw networkFailure();
    } catch (Exception failure) {
      exchange.cancel(true);
      throw networkFailure();
    }
  }

  private static DeletionOperationException networkFailure() {
    return DeletionOperationException.retryable("SUPABASE_NETWORK_FAILURE");
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
    }
    return false;
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximumBodyBytes;
    private final ByteArrayOutputStream body;
    private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private BoundedBodySubscriber(int maximumBodyBytes) {
      if (maximumBodyBytes < 0) {
        throw new IllegalArgumentException("maximumBodyBytes는 음수일 수 없습니다.");
      }
      this.maximumBodyBytes = maximumBodyBytes;
      this.body = new ByteArrayOutputStream(Math.min(maximumBodyBytes, 8192));
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return completed;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (completed.isDone()) {
        return;
      }
      for (ByteBuffer buffer : buffers) {
        int remaining = buffer.remaining();
        if (remaining > maximumBodyBytes - body.size()) {
          subscription.cancel();
          completed.completeExceptionally(BodyTooLarge.INSTANCE);
          return;
        }
        byte[] chunk = new byte[remaining];
        buffer.get(chunk);
        body.writeBytes(chunk);
      }
    }

    @Override
    public void onError(Throwable failure) {
      completed.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      completed.complete(body.toByteArray());
    }
  }

  private static final class BodyTooLarge extends RuntimeException {
    private static final BodyTooLarge INSTANCE = new BodyTooLarge();

    private BodyTooLarge() {
      super(null, null, false, false);
    }
  }
}
