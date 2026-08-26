package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.EOFException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FirebaseAdminMessagingGatewayRawRequestTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneOffset.UTC);

  @Test
  void 성공도_raw_request_exact1이고_notification_data_wrapper와_message_id를_보존한다() {
    CountingTransport transport =
        CountingTransport.responding(
            new MockLowLevelHttpResponse()
                .setStatusCode(200)
                .setContentType("application/json")
                .setContent("{\"name\":\"projects/p/messages/42\"}"));

    FirebaseCallResult result = gateway(transport).send(mappedMessage());

    assertThat(transport.rawRequestCount()).isOne();
    JsonObject outer = JsonParser.parseString(transport.lastRequestBody()).getAsJsonObject();
    assertThat(outer.keySet()).containsExactly("message");
    JsonObject wireMessage = outer.getAsJsonObject("message");
    assertThat(wireMessage.getAsJsonObject("notification").keySet())
        .containsExactlyInAnyOrder("title", "body");
    assertThat(wireMessage.getAsJsonObject("data").keySet())
        .containsExactlyInAnyOrder(
            "contractVersion", "tripId", "tripItemId", "scheduleVersionId", "deepLink");
    assertThat(wireMessage.getAsJsonObject("android").get("priority").getAsString())
        .isEqualTo("high");
    JsonObject apns = wireMessage.getAsJsonObject("apns");
    assertThat(apns.getAsJsonObject("headers").keySet())
        .containsExactlyInAnyOrder("apns-expiration", "apns-collapse-id");
    assertThat(apns.getAsJsonObject("payload").getAsJsonObject("aps").get("sound").getAsString())
        .isEqualTo("default");
    assertThat(result.providerMessageId()).isEqualTo("projects/p/messages/42");
  }

  @Test
  void explicit_503은_SDK_내부_retry없이_raw_request_exact1이다() {
    CountingTransport transport =
        CountingTransport.responding(
            new MockLowLevelHttpResponse()
                .setStatusCode(503)
                .setContentType("application/json")
                .setContent("{\"error\":{\"status\":\"UNAVAILABLE\"}}"));

    FirebaseCallResult result = gateway(transport).send(mappedMessage());

    assertThat(transport.rawRequestCount()).isOne();
    assertThat(result.failure().httpStatus()).isEqualTo(503);
  }

  @Test
  void explicit_429도_raw_request_exact1이고_retry_after를_보존한다() {
    CountingTransport transport =
        CountingTransport.responding(
            new MockLowLevelHttpResponse()
                .setStatusCode(429)
                .addHeader("Retry-After", "17")
                .setContentType("application/json")
                .setContent("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"));

    FirebaseCallResult result = gateway(transport).send(mappedMessage());

    assertThat(transport.rawRequestCount()).isOne();
    assertThat(result.failure().retryAfter()).isEqualTo(java.time.Duration.ofSeconds(17));
  }

  @Test
  void provider_error_detail의_UNREGISTERED를_token_invalidation으로_연결한다() {
    CountingTransport transport =
        CountingTransport.responding(
            new MockLowLevelHttpResponse()
                .setStatusCode(404)
                .setContentType("application/json")
                .setContent(
                    "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}"));

    FirebaseCallResult call = gateway(transport).send(mappedMessage());
    com.timingjeju.api.application.push.PushSendResult result =
        new FirebaseErrorClassifier().classify(call.failure());

    assertThat(transport.rawRequestCount()).isOne();
    assertThat(result)
        .isEqualTo(
            new com.timingjeju.api.application.push.PushSendResult.PermanentFailure(
                com.timingjeju.api.application.push.PushErrorClass.TOKEN_UNREGISTERED, true));
  }

  @Test
  void post_write_ambiguity도_raw_request_exact1이고_자동_retry하지_않는다() {
    CountingTransport transport = CountingTransport.failing(new EOFException("unexpected eof"));

    FirebaseCallResult result = gateway(transport).send(mappedMessage());

    assertThat(transport.rawRequestCount()).isOne();
    assertThat(result.failure().kind()).isEqualTo(FirebaseFailureKind.POST_WRITE_AMBIGUOUS);
  }

  private static FirebaseAdminMessagingGateway gateway(HttpTransport transport) {
    return new FirebaseAdminMessagingGateway(
        transport.createRequestFactory(),
        GsonFactory.getDefaultInstance(),
        "timing-jeju-test",
        2_000,
        5_000,
        5_000,
        CLOCK);
  }

  private static com.google.firebase.messaging.Message mappedMessage() {
    return new FirebaseMessageMapper(CLOCK)
        .map(FirebasePushMessageSenderTest.messageForGatewayTest());
  }

  private static final class CountingTransport extends HttpTransport {
    private final AtomicInteger count = new AtomicInteger();
    private final MockLowLevelHttpResponse response;
    private final IOException failure;
    private String lastRequestBody;

    private CountingTransport(MockLowLevelHttpResponse response, IOException failure) {
      this.response = response;
      this.failure = failure;
    }

    static CountingTransport responding(MockLowLevelHttpResponse response) {
      return new CountingTransport(response, null);
    }

    static CountingTransport failing(IOException failure) {
      return new CountingTransport(null, failure);
    }

    int rawRequestCount() {
      return count.get();
    }

    String lastRequestBody() {
      return lastRequestBody;
    }

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) {
      return new MockLowLevelHttpRequest(url) {
        @Override
        public LowLevelHttpResponse execute() throws IOException {
          lastRequestBody = getContentAsString();
          count.incrementAndGet();
          if (failure != null) throw failure;
          return response;
        }
      };
    }
  }
}
