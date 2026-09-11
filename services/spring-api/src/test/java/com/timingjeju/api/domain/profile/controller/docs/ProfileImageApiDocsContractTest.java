package com.timingjeju.api.domain.profile.controller.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ProfileImageApiDocsContractTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void 프로필_이미지_API는_안정적인_operationId와_tag를_제공한다() throws Exception {
    Operation read =
        ProfileImageApiDocs.class
            .getDeclaredMethod("read", jakarta.servlet.http.HttpServletRequest.class)
            .getAnnotation(Operation.class);
    Operation update =
        ProfileImageApiDocs.class
            .getDeclaredMethod(
                "apply", String.class, String.class, jakarta.servlet.http.HttpServletRequest.class)
            .getAnnotation(Operation.class);

    assertThat(read.operationId()).isEqualTo("profileImageRead");
    assertThat(read.tags()).containsExactly("프로필 이미지");
    assertThat(update.operationId()).isEqualTo("profileImageUpdate");
    assertThat(update.tags()).containsExactly("프로필 이미지");
  }

  @Test
  void GET_PUT_problem_response는_status_code와_canonical_8필드_example을_양방향으로_고정한다() throws Exception {
    assertProblems(
        ProfileImageApiDocs.class.getDeclaredMethod(
            "read", jakarta.servlet.http.HttpServletRequest.class),
        Map.of(
            "401",
                Map.of(
                    "AUTHENTICATION_REQUIRED", problem("인증이 필요합니다", 401, "로그인 후 다시 요청해 주세요."),
                    "INVALID_ACCESS_TOKEN",
                        problem("인증 정보가 올바르지 않습니다", 401, "유효한 인증 정보로 다시 요청해 주세요.")),
            "503",
                Map.of(
                    "PROFILE_DATA_UNAVAILABLE",
                    problem("프로필 조회 불가", 503, "프로필 데이터를 불러올 수 없습니다."))));

    assertProblems(
        ProfileImageApiDocs.class.getDeclaredMethod(
            "apply", String.class, String.class, jakarta.servlet.http.HttpServletRequest.class),
        Map.of(
            "400",
                Map.of(
                    "INVALID_PROFILE_IMAGE_REQUEST",
                    problem("프로필 이미지 요청 오류", 400, "프로필 이미지 요청 형식이 올바르지 않습니다.")),
            "401",
                Map.of(
                    "AUTHENTICATION_REQUIRED", problem("인증이 필요합니다", 401, "로그인 후 다시 요청해 주세요."),
                    "INVALID_ACCESS_TOKEN",
                        problem("인증 정보가 올바르지 않습니다", 401, "유효한 인증 정보로 다시 요청해 주세요.")),
            "404",
                Map.of(
                    "PROFILE_IMAGE_NOT_FOUND",
                    problem("프로필 이미지 없음", 404, "확정할 프로필 이미지를 찾을 수 없습니다.")),
            "409",
                Map.of(
                    "PROFILE_IMAGE_VERSION_CONFLICT",
                        problem("프로필 이미지 버전 충돌", 409, "프로필 이미지 상태가 변경되었습니다."),
                    "IDEMPOTENCY_PAYLOAD_CONFLICT",
                        problem("멱등 요청 충돌", 409, "같은 멱등성 키의 요청 본문이 다릅니다."),
                    "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                        problem("멱등 요청 처리 중", 409, "동일한 요청이 처리 중입니다.")),
            "413",
                Map.of(
                    "PROFILE_IMAGE_TOO_LARGE",
                    problem("프로필 이미지 용량 초과", 413, "프로필 이미지는 5 MiB 이하여야 합니다.")),
            "415",
                Map.of(
                    "PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED",
                    problem("프로필 이미지 형식 오류", 415, "지원하지 않는 프로필 이미지 형식입니다.")),
            "503",
                Map.of(
                    "PROFILE_IMAGE_STORAGE_UNAVAILABLE",
                    problem("프로필 이미지 저장소 오류", 503, "프로필 이미지 저장소를 확인할 수 없습니다."))));
  }

  private static Problem problem(String title, int status, String detail) {
    return new Problem(title, status, detail);
  }

  private static void assertProblems(Method method, Map<String, Map<String, Problem>> expected)
      throws Exception {
    Map<String, ApiResponse> responses =
        Arrays.stream(method.getAnnotation(ApiResponses.class).value())
            .filter(response -> !response.responseCode().startsWith("2"))
            .collect(
                Collectors.toMap(
                    ApiResponse::responseCode,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    assertThat(responses.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());

    for (Map.Entry<String, Map<String, Problem>> status : expected.entrySet()) {
      Content content = responses.get(status.getKey()).content()[0];
      assertThat(content.mediaType()).isEqualTo("application/problem+json");
      assertThat(content.schema().implementation()).isEqualTo(ApiProblemDetails.class);
      Map<String, ExampleObject> examples =
          Arrays.stream(content.examples())
              .collect(Collectors.toMap(ExampleObject::name, Function.identity()));
      assertThat(examples.keySet()).containsExactlyInAnyOrderElementsOf(status.getValue().keySet());

      for (Map.Entry<String, Problem> canonical : status.getValue().entrySet()) {
        JsonNode body = JSON.readTree(examples.get(canonical.getKey()).value());
        assertThat(body.propertyNames())
            .containsExactlyInAnyOrder(
                "type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors");
        assertThat(body.get("type").asText())
            .isEqualTo(
                "https://api.timing-jeju.com/problems/"
                    + canonical.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        assertThat(body.get("title").asText()).isEqualTo(canonical.getValue().title());
        assertThat(body.get("status").asInt()).isEqualTo(canonical.getValue().status());
        assertThat(body.get("detail").asText()).isEqualTo(canonical.getValue().detail());
        assertThat(body.get("code").asText()).isEqualTo(canonical.getKey());
        assertThat(body.get("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(body.get("instance").asText())
            .isEqualTo("urn:timing-jeju:problem:" + body.get("traceId").asText());
        assertThat(body.get("fieldErrors").isArray()).isTrue();
        assertThat(body.get("fieldErrors").size()).isZero();
      }
    }
  }

  private record Problem(String title, int status, String detail) {}
}
