package com.timingjeju.api.global.error;

import static com.timingjeju.api.support.http.ProblemDetailsAssertions.problemDetails;
import static com.timingjeju.api.support.http.ProblemDetailsAssertions.problemDetailsWithFieldErrors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.timingjeju.api.application.pagination.CursorInvalidException;
import com.timingjeju.api.global.logging.RequestTraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000"
    })
class ProblemDetailsIntegrationTest {

  private static final String SENSITIVE_EXCEPTION_MESSAGE =
      "provider-token=must-not-appear user@example.test";

  @Autowired private GlobalProblemExceptionHandler exceptionHandler;
  @Autowired private RequestTraceIdFilter traceIdFilter;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ContractTestController())
            .setControllerAdvice(exceptionHandler)
            .addFilter(traceIdFilter)
            .build();
  }

  @Test
  void Controller_validation_실패는_한국어_fieldErrors를_field_detail_순으로_정렬한다() throws Exception {
    mockMvc
        .perform(
            post("/problem-test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"", "minimum":0, "maximum":11}
                    """))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors.length()").value(3))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("maximum"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("최댓값 조건을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("minimum"))
        .andExpect(jsonPath("$.fieldErrors[1].detail").value("최솟값 조건을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors[2].field").value("name"))
        .andExpect(jsonPath("$.fieldErrors[2].detail").value("필수 입력값입니다."));
  }

  @Test
  void 잘못된_JSON_타입은_raw_Jackson_예외_없이_validation_400을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/problem-test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"제주", "minimum":"raw-secret", "maximum":10}
                    """))
        .andExpectAll(
            problemDetails(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(content().string(not(containsString("raw-secret"))))
        .andExpect(content().string(not(containsString("Jackson"))));
  }

  @Test
  void method_parameter_validation도_같은_400_계약과_field_path를_사용한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/parameter").queryParam("size", "0"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors.length()").value(1))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("size"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("최솟값 조건을 확인해 주세요."));
  }

  @Test
  void method_collection_element_validation은_숫자_index를_field_path에_보존한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/list-parameter").queryParam("sizes", "0", "2"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors.length()").value(1))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("sizes[0]"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("최솟값 조건을 확인해 주세요."));
  }

  @Test
  void cross_parameter_validation은_request_field의_고정_detail로_반환한다() throws Exception {
    var method = ContractTestController.class.getDeclaredMethod("validateParameter", int.class);
    var result =
        MethodValidationResult.create(
            new ContractTestController(),
            method,
            List.of(),
            List.of(
                new DefaultMessageSourceResolvable(
                    new String[] {"ValidRange"}, null, "raw-user@example.test")));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problem-test/range");
    MockHttpServletResponse response = new MockHttpServletResponse();

    exceptionHandler.handleMethodValidation(
        new HandlerMethodValidationException(result), request, response);

    String body = response.getContentAsString();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(JsonPath.<List<Object>>read(body, "$.fieldErrors")).hasSize(1);
    assertThat(JsonPath.<String>read(body, "$.fieldErrors[0].field")).isEqualTo("request");
    assertThat(JsonPath.<String>read(body, "$.fieldErrors[0].detail")).isEqualTo("유효하지 않은 값입니다.");
    assertThat(body).doesNotContain("raw-user@example.test");
  }

  @Test
  void 누락된_request_parameter는_고정된_한국어_validation_400을_반환한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/parameter"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors.length()").value(1))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("size"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("필수 요청 파라미터입니다."));
  }

  @Test
  void 누락된_request_header도_validation_400으로_분류한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/required-header"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors.length()").value(1))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("X-Required"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("필수 요청 헤더입니다."));
  }

  @Test
  void 누락된_cookie와_multipart도_validation_400으로_분류한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/required-cookie"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("required-cookie"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("필수 요청 쿠키입니다."));

    mockMvc
        .perform(multipart("/problem-test/required-part"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("file"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("필수 요청 파트입니다."));
  }

  @Test
  void 중첩_배열_validation_path와_고정_한국어_detail을_보존한다() throws Exception {
    mockMvc
        .perform(
            post("/problem-test/nested-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"name\":\"\"}]}"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors.length()").value(1))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("items[0].name"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("필수 입력값입니다."));
  }

  @Test
  void 지원하지_않는_method는_Allow_header와_405_계약을_반환한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/validation"))
        .andExpectAll(
            problemDetails(
                405,
                "https://api.timing-jeju.example/problems/method-not-allowed",
                "허용되지 않은 요청 방식입니다.",
                "METHOD_NOT_ALLOWED",
                "지원되는 HTTP 메서드로 요청해 주세요."))
        .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
  }

  @Test
  void 지원하지_않는_Content_Type은_raw_body없이_415_계약을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/problem-test/validation")
                .contentType(MediaType.TEXT_PLAIN)
                .content("provider-token-user@example.test"))
        .andExpectAll(
            problemDetails(
                415,
                "https://api.timing-jeju.example/problems/unsupported-media-type",
                "지원하지 않는 미디어 형식입니다.",
                "UNSUPPORTED_MEDIA_TYPE",
                "지원되는 Content-Type으로 요청해 주세요."))
        .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")))
        .andExpect(content().string(not(containsString("provider-token-user@example.test"))));
  }

  @Test
  void 허용하지_않는_Accept는_406_계약을_반환한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/produces-json").accept(MediaType.TEXT_PLAIN))
        .andExpectAll(
            problemDetails(
                406,
                "https://api.timing-jeju.example/problems/not-acceptable",
                "요청한 응답 형식을 제공할 수 없습니다.",
                "NOT_ACCEPTABLE",
                "지원되는 Accept 형식으로 요청해 주세요."))
        .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")));
  }

  @Test
  void ResponseStatusException은_reason을_숨기고_registry의_404로_변환한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/response-status"))
        .andExpectAll(
            problemDetails(
                404,
                "https://api.timing-jeju.example/problems/resource-not-found",
                "요청한 리소스를 찾을 수 없습니다.",
                "RESOURCE_NOT_FOUND",
                "요청한 리소스가 존재하지 않습니다."))
        .andExpect(content().string(not(containsString(SENSITIVE_EXCEPTION_MESSAGE))));
  }

  @Test
  void 확장_status도_안정적인_registry_code로_변환한다() throws Exception {
    List<ExpectedStatusProblem> expectedProblems =
        List.of(
            new ExpectedStatusProblem(
                422,
                "unprocessable-entity",
                "요청 내용을 처리할 수 없습니다.",
                "UNPROCESSABLE_ENTITY",
                "요청 내용을 확인해 주세요."),
            new ExpectedStatusProblem(
                424,
                "failed-dependency",
                "선행 요청을 완료할 수 없습니다.",
                "FAILED_DEPENDENCY",
                "잠시 후 다시 시도해 주세요."),
            new ExpectedStatusProblem(
                429, "too-many-requests", "요청이 너무 많습니다.", "TOO_MANY_REQUESTS", "잠시 후 다시 시도해 주세요."),
            new ExpectedStatusProblem(
                503,
                "service-unavailable",
                "서비스를 일시적으로 사용할 수 없습니다.",
                "SERVICE_UNAVAILABLE",
                "잠시 후 다시 시도해 주세요."),
            new ExpectedStatusProblem(
                504,
                "upstream-timeout",
                "외부 서비스 응답이 지연되고 있습니다.",
                "UPSTREAM_TIMEOUT",
                "잠시 후 다시 시도해 주세요."));

    for (ExpectedStatusProblem expected : expectedProblems) {
      mockMvc
          .perform(get("/problem-test/response-status/{status}", expected.status()))
          .andExpectAll(
              problemDetails(
                  expected.status(),
                  "https://api.timing-jeju.example/problems/" + expected.typeSuffix(),
                  expected.title(),
                  expected.code(),
                  expected.detail()))
          .andExpect(content().string(not(containsString(SENSITIVE_EXCEPTION_MESSAGE))));
    }
  }

  @Test
  void Controller_return_value_validation은_constraint_message없이_고정_500을_반환한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/return-value"))
        .andExpectAll(
            problemDetails(
                500,
                "https://api.timing-jeju.example/problems/internal-server-error",
                "내부 서버 오류가 발생했습니다.",
                "INTERNAL_SERVER_ERROR",
                "요청을 처리하는 중 내부 오류가 발생했습니다."))
        .andExpect(content().string(not(containsString(SENSITIVE_EXCEPTION_MESSAGE))));
  }

  @Test
  void validation_message가_rejected_value를_포함하면_고정_detail로_대체한다() throws Exception {
    mockMvc
        .perform(
            post("/problem-test/sensitive-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"provider-token-user@example.test\"}"))
        .andExpectAll(
            problemDetailsWithFieldErrors(
                400,
                "https://api.timing-jeju.example/problems/validation-failed",
                "요청 값이 올바르지 않습니다.",
                "VALIDATION_FAILED",
                "입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("value"))
        .andExpect(jsonPath("$.fieldErrors[0].detail").value("입력 형식이 올바르지 않습니다."))
        .andExpect(content().string(not(containsString("provider-token-user@example.test"))));
  }

  @Test
  void async_Controller_worker와_응답_header는_같은_traceId를_사용한다() throws Exception {
    MvcResult initial =
        mockMvc
            .perform(get("/problem-test/async-trace"))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc
        .perform(asyncDispatch(initial))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .isEqualTo(result.getResponse().getHeader("X-Trace-Id")));
  }

  @Test
  void registry의_not_found_conflict_upstream_mapping을_공통_writer가_사용한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/not-found?token=query-secret"))
        .andExpectAll(
            problemDetails(
                404,
                "https://api.timing-jeju.example/problems/resource-not-found",
                "요청한 리소스를 찾을 수 없습니다.",
                "RESOURCE_NOT_FOUND",
                "요청한 리소스가 존재하지 않습니다."))
        .andExpect(content().string(not(containsString("query-secret"))));

    mockMvc
        .perform(get("/problem-test/conflict"))
        .andExpectAll(
            problemDetails(
                409,
                "https://api.timing-jeju.example/problems/conflict",
                "요청이 현재 상태와 충돌합니다.",
                "CONFLICT",
                "최신 상태를 확인한 뒤 다시 시도해 주세요."));

    mockMvc
        .perform(get("/problem-test/upstream"))
        .andExpectAll(
            problemDetails(
                502,
                "https://api.timing-jeju.example/problems/upstream-error",
                "외부 서비스 요청을 완료하지 못했습니다.",
                "UPSTREAM_ERROR",
                "외부 서비스 응답을 처리할 수 없습니다."));
  }

  @Test
  void 잘못된_cursor는_공통_Problem_Details의_CURSOR_INVALID로_반환한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/cursor-invalid"))
        .andExpectAll(
            problemDetails(
                400,
                "https://api.timing-jeju.example/problems/cursor-invalid",
                "커서가 유효하지 않습니다.",
                "CURSOR_INVALID",
                "목록을 처음부터 다시 조회해 주세요."));
  }

  @Test
  void 예상하지_못한_예외는_raw_message_PII_provider_payload_없이_고정_500을_반환한다() throws Exception {
    mockMvc
        .perform(get("/problem-test/unknown"))
        .andExpectAll(
            problemDetails(
                500,
                "https://api.timing-jeju.example/problems/internal-server-error",
                "내부 서버 오류가 발생했습니다.",
                "INTERNAL_SERVER_ERROR",
                "요청을 처리하는 중 내부 오류가 발생했습니다."))
        .andExpect(content().string(not(containsString(SENSITIVE_EXCEPTION_MESSAGE))))
        .andExpect(content().string(not(containsString("user@example.test"))));
  }

  @RestController
  static class ContractTestController {

    @PostMapping("/problem-test/validation")
    void validate(@Valid @RequestBody ValidationRequest request) {}

    @GetMapping("/problem-test/parameter")
    void validateParameter(
        @RequestParam @Min(value = 1, message = "size는 1 이상이어야 합니다.") int size) {}

    @GetMapping("/problem-test/list-parameter")
    void validateListParameter(
        @RequestParam List<@Min(value = 1, message = "raw-user@example.test") Integer> sizes) {}

    @GetMapping("/problem-test/required-header")
    void requiredHeader(@RequestHeader("X-Required") String requiredHeader) {}

    @GetMapping("/problem-test/required-cookie")
    void requiredCookie(@CookieValue("required-cookie") String requiredCookie) {}

    @PostMapping(
        value = "/problem-test/required-part",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    void requiredPart(@RequestPart("file") MultipartFile file) {}

    @PostMapping("/problem-test/nested-validation")
    void nestedValidation(@Valid @RequestBody NestedValidationRequest request) {}

    @GetMapping("/problem-test/not-found")
    void notFound() {
      throw new ApiProblemException("RESOURCE_NOT_FOUND");
    }

    @GetMapping("/problem-test/conflict")
    void conflict() {
      throw new ApiProblemException("CONFLICT");
    }

    @GetMapping("/problem-test/upstream")
    void upstream() {
      throw new ApiProblemException("UPSTREAM_ERROR");
    }

    @GetMapping("/problem-test/cursor-invalid")
    void cursorInvalid() {
      throw new CursorInvalidException();
    }

    @GetMapping("/problem-test/unknown")
    void unknown() {
      throw new IllegalStateException(SENSITIVE_EXCEPTION_MESSAGE);
    }

    @GetMapping(value = "/problem-test/produces-json", produces = MediaType.APPLICATION_JSON_VALUE)
    String producesJson() {
      return "{}";
    }

    @GetMapping("/problem-test/response-status")
    void responseStatus() {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, SENSITIVE_EXCEPTION_MESSAGE);
    }

    @GetMapping("/problem-test/response-status/{status}")
    void responseStatus(@PathVariable int status) {
      throw new ResponseStatusException(
          HttpStatusCode.valueOf(status), SENSITIVE_EXCEPTION_MESSAGE);
    }

    @GetMapping("/problem-test/return-value")
    @NotBlank(message = SENSITIVE_EXCEPTION_MESSAGE)
    String invalidReturnValue() {
      return "";
    }

    @PostMapping("/problem-test/sensitive-validation")
    void validateSensitive(@Valid @RequestBody SensitiveValidationRequest request) {}

    @GetMapping("/problem-test/async-trace")
    Callable<String> asyncTrace() {
      return () -> MDC.get("traceId");
    }
  }

  record ValidationRequest(
      @NotBlank(message = "이름은 필수입니다.") String name,
      @Min(value = 1, message = "최솟값은 1입니다.") int minimum,
      @Max(value = 10, message = "최댓값은 10입니다.") int maximum) {}

  record SensitiveValidationRequest(
      @Pattern(regexp = "allowed-value", message = "검증 실패: provider-token-user@example.test")
          String value) {}

  record NestedValidationRequest(@Valid List<NestedItem> items) {}

  record NestedItem(@NotBlank String name) {}

  record ExpectedStatusProblem(
      int status, String typeSuffix, String title, String code, String detail) {}
}
