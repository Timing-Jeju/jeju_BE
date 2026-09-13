package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SupabaseDeletionGatewaysTest {
  private static final String SUBJECT = "46d9a0ca-3472-4f7e-b1b8-b751da5a7f40";
  private static final String CREDENTIAL = "test-value-106";

  @Test
  void auth_admin_delete는_공식_endpoint와_service_role_header를_사용하고_404도_성공이다() {
    RecordingTransport transport = new RecordingTransport(response(404, "{}"));
    var gateway = new SupabaseAuthAdminHttpGateway(settings(10), transport);

    assertThat(gateway.deleteUser(AuthSubject.of(SUBJECT)))
        .isEqualTo(ExternalDeletionResult.ALREADY_ABSENT);

    HttpRequest request = transport.requests.getFirst();
    assertThat(request.method()).isEqualTo("DELETE");
    assertThat(request.uri().getPath()).isEqualTo("/auth/v1/admin/users/" + SUBJECT);
    assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + CREDENTIAL);
    assertThat(request.headers().firstValue("apikey")).contains(CREDENTIAL);
    assertThat(request.toString()).doesNotContain(CREDENTIAL);
  }

  @Test
  void auth_admin_delete는_429_5xx_network를_retryable_그외_4xx를_terminal로_분류한다() {
    assertFailure(
        new SupabaseAuthAdminHttpGateway(settings(10), new RecordingTransport(response(429, ""))),
        true);
    assertFailure(
        new SupabaseAuthAdminHttpGateway(settings(10), new RecordingTransport(response(503, ""))),
        true);
    assertFailure(
        new SupabaseAuthAdminHttpGateway(
            settings(10), new RecordingTransport(response(401, CREDENTIAL))),
        false);

    SupabaseAdminHttpTransport network =
        (request, body, limit) -> {
          throw DeletionOperationException.retryable("SUPABASE_NETWORK_FAILURE");
        };
    assertFailure(new SupabaseAuthAdminHttpGateway(settings(10), network), true);
  }

  @Test
  void storage는_canonical_user_prefix를_page_list하고_1000개_이하_batch로_delete한다() {
    RecordingTransport transport =
        new RecordingTransport(
            response(200, "[{\"id\":null,\"name\":\"profile\"}]"),
            response(200, "[{\"id\":\"object-1\",\"name\":\"generation-a\"}]"),
            response(200, "[]"));
    var gateway =
        new SupabaseProfileImageDeletionHttpGateway(settings(10), new ObjectMapper(), transport);

    assertThat(gateway.deletePrefix("profile-images/" + SUBJECT))
        .isEqualTo(ExternalDeletionResult.DELETED);

    assertThat(transport.requests).hasSize(3);
    assertThat(transport.requests.get(0).uri().getPath())
        .isEqualTo("/storage/v1/object/list/profile-images");
    assertThat(transport.bodies.get(0)).contains("\"prefix\":\"" + SUBJECT + "\"");
    assertThat(transport.bodies.get(1)).contains("\"prefix\":\"" + SUBJECT + "/profile\"");
    assertThat(transport.requests.get(2).method()).isEqualTo("DELETE");
    assertThat(transport.requests.get(2).uri().getPath())
        .isEqualTo("/storage/v1/object/profile-images");
    assertThat(transport.bodies.get(2)).contains(SUBJECT + "/profile/generation-a");
  }

  @Test
  void storage는_빈값_traversal_비UUID_subject와_pagination_상한을_fail_closed한다() {
    RecordingTransport unused = new RecordingTransport();
    var gateway =
        new SupabaseProfileImageDeletionHttpGateway(settings(2), new ObjectMapper(), unused);

    assertThatThrownBy(() -> gateway.deletePrefix("profile-images/../secrets"))
        .isInstanceOf(DeletionOperationException.class)
        .hasMessage("INVALID_AUTH_SUBJECT");
    assertThat(unused.requests).isEmpty();

    RecordingTransport looping =
        new RecordingTransport(
            response(200, "[{\"id\":null,\"name\":\"profile\"}]"),
            response(200, "[{\"id\":null,\"name\":\"nested\"}]"));
    var bounded =
        new SupabaseProfileImageDeletionHttpGateway(settings(2), new ObjectMapper(), looping);
    assertThatThrownBy(() -> bounded.deletePrefix("profile-images/" + SUBJECT))
        .isInstanceOf(DeletionOperationException.class)
        .hasMessage("STORAGE_PAGINATION_LIMIT_EXCEEDED");
  }

  @Test
  void settings와_failure는_service_role_secret을_노출하지_않는다() {
    SupabaseAdminSettings settings = settings(10);
    assertThat(settings.toString()).doesNotContain(CREDENTIAL).contains("<redacted>");

    RecordingTransport transport = new RecordingTransport(response(400, CREDENTIAL));
    assertThatThrownBy(
            () ->
                new SupabaseAuthAdminHttpGateway(settings, transport)
                    .deleteUser(AuthSubject.of(SUBJECT)))
        .isInstanceOf(DeletionOperationException.class)
        .hasMessage("SUPABASE_AUTH_DELETE_REJECTED")
        .hasNoCause();
  }

  @Test
  void settings는_https와_loopback_http만_허용한다() {
    assertThatCode(
            () ->
                SupabaseAdminSettings.enabled(
                    URI.create("http://127.0.0.1:54321"),
                    CREDENTIAL,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    1000,
                    10))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                SupabaseAdminSettings.enabled(
                    URI.create("http://supabase.example.com"),
                    CREDENTIAL,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    1000,
                    10))
        .isInstanceOf(IllegalStateException.class);
  }

  private static void assertFailure(SupabaseAuthAdminHttpGateway gateway, boolean retryable) {
    assertThatThrownBy(() -> gateway.deleteUser(AuthSubject.of(SUBJECT)))
        .isInstanceOfSatisfying(
            DeletionOperationException.class,
            failure -> assertThat(failure.isRetryable()).isEqualTo(retryable))
        .hasNoCause();
  }

  private static SupabaseAdminSettings settings(int maxPages) {
    return SupabaseAdminSettings.enabled(
        URI.create("https://project.supabase.co"),
        CREDENTIAL,
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        1000,
        maxPages);
  }

  private static SupabaseAdminHttpResponse response(int status, String body) {
    return new SupabaseAdminHttpResponse(status, body.getBytes(StandardCharsets.UTF_8));
  }

  private static final class RecordingTransport implements SupabaseAdminHttpTransport {
    private final ArrayDeque<SupabaseAdminHttpResponse> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    private RecordingTransport(SupabaseAdminHttpResponse... responses) {
      this.responses.addAll(List.of(responses));
    }

    @Override
    public SupabaseAdminHttpResponse exchange(
        HttpRequest request, byte[] body, int maximumBodyBytes) {
      requests.add(request);
      bodies.add(new String(body, StandardCharsets.UTF_8));
      return responses.removeFirst();
    }
  }
}
