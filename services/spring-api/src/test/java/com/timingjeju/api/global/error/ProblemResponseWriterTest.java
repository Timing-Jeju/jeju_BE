package com.timingjeju.api.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.timingjeju.api.global.logging.RequestTraceId;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class ProblemResponseWriterTest {

  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

  private final RequestTraceId requestTraceId = new RequestTraceId(() -> TRACE_ID);
  private final ProblemResponseWriter writer =
      new ProblemResponseWriter(
          new ObjectMapper(), new ProblemCodeRegistry(List.of()), requestTraceId);

  @Test
  void fieldErrors는_field와_detail로_안정적으로_정렬하고_message를_쓰지_않는다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean written =
        writer.write(
            request,
            response,
            "VALIDATION_FAILED",
            List.of(
                new FieldErrorDetail("zeta", "두 번째"),
                new FieldErrorDetail("alpha", "첫 번째"),
                new FieldErrorDetail("alpha", "세 번째")));

    assertThat(written).isTrue();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getHeader(RequestTraceId.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
    String body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.traceId")).isEqualTo(TRACE_ID);
    assertThat(JsonPath.<List<String>>read(body, "$.fieldErrors[*].field"))
        .containsExactly("alpha", "alpha", "zeta");
    assertThat(JsonPath.<List<String>>read(body, "$.fieldErrors[*].detail"))
        .containsExactly("세 번째", "첫 번째", "두 번째");
    assertThat(JsonPath.<List<Object>>read(body, "$..message")).isEmpty();
  }

  @Test
  void 이미_committed된_응답에는_status_header_body를_중복해_쓰지_않는다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.getWriter().write("existing-response");
    response.flushBuffer();

    boolean written = writer.write(request, response, "INTERNAL_SERVER_ERROR");

    assertThat(written).isFalse();
    assertThat(response.getContentAsString()).isEqualTo("existing-response");
    assertThat(response.getHeader(RequestTraceId.TRACE_ID_HEADER)).isNull();
  }

  @Test
  void 미commit_partial_writer의_민감정보를_지우고_Problem_Details만_쓴다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.getWriter().write("partial-user@example.test");

    boolean written = writer.write(request, response, "INTERNAL_SERVER_ERROR");

    assertThat(written).isTrue();
    assertThat(response.getContentAsString()).doesNotContain("partial-user@example.test");
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code"))
        .isEqualTo("INTERNAL_SERVER_ERROR");
  }

  @Test
  void 미commit_partial_output_stream의_민감정보를_지우고_Problem_Details만_쓴다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.getOutputStream().write("partial-provider-token".getBytes());

    boolean written = writer.write(request, response, "INTERNAL_SERVER_ERROR");

    assertThat(written).isTrue();
    assertThat(response.getContentAsString()).doesNotContain("partial-provider-token");
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code"))
        .isEqualTo("INTERNAL_SERVER_ERROR");
  }

  @Test
  void instance는_raw_path_대신_발생별_traceId_URN을_사용한다() throws Exception {
    MockHttpServletRequest matchedRequest =
        new MockHttpServletRequest("GET", "/api/v1/users/user@example.test");
    MockHttpServletResponse matchedResponse = new MockHttpServletResponse();

    writer.write(matchedRequest, matchedResponse, "RESOURCE_NOT_FOUND");

    assertThat(JsonPath.<String>read(matchedResponse.getContentAsString(), "$.instance"))
        .isEqualTo("urn:timing-jeju:problem:" + TRACE_ID);
    assertThat(matchedResponse.getContentAsString()).doesNotContain("user@example.test");

    MockHttpServletRequest unmatchedRequest =
        new MockHttpServletRequest("GET", "/api/v1/provider-token-user@example.test");
    MockHttpServletResponse unmatchedResponse = new MockHttpServletResponse();

    writer.write(unmatchedRequest, unmatchedResponse, "INVALID_ACCESS_TOKEN");

    assertThat(JsonPath.<String>read(unmatchedResponse.getContentAsString(), "$.instance"))
        .isEqualTo("urn:timing-jeju:problem:" + TRACE_ID);
    assertThat(unmatchedResponse.getContentAsString())
        .doesNotContain("provider-token", "user@example.test");
  }
}
