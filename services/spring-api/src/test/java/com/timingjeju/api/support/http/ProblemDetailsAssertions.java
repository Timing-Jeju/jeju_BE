package com.timingjeju.api.support.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;

public final class ProblemDetailsAssertions {

  public static final String TRACE_ID_PATTERN = "[0-9a-f]{32}";
  public static final String TRACE_ID_HEADER = "X-Trace-Id";
  public static final String PROBLEM_INSTANCE_PREFIX = "urn:timing-jeju:problem:";

  private ProblemDetailsAssertions() {}

  public static ResultMatcher[] problemDetails(
      int httpStatus, String type, String title, String code, String detail) {
    return Stream.concat(
            Stream.of(problemDetailsWithFieldErrors(httpStatus, type, title, code, detail)),
            Stream.of(jsonPath("$.fieldErrors").isEmpty()))
        .toArray(ResultMatcher[]::new);
  }

  public static ResultMatcher[] problemDetailsWithFieldErrors(
      int httpStatus, String type, String title, String code, String detail) {
    return new ResultMatcher[] {
      status().is(httpStatus),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.type").value(type),
      jsonPath("$.title").value(title),
      jsonPath("$.status").value(httpStatus),
      jsonPath("$.detail").value(detail),
      jsonPath("$.instance").value(matchesPattern(PROBLEM_INSTANCE_PREFIX + TRACE_ID_PATTERN)),
      jsonPath("$.code").value(code),
      jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)),
      jsonPath("$.fieldErrors").isArray(),
      jsonPath("$").value(aMapWithSize(8)),
      jsonPath("$..message").doesNotExist(),
      header().string(TRACE_ID_HEADER, matchesPattern(TRACE_ID_PATTERN)),
      result -> {
        String bodyTraceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");
        assertThat(result.getResponse().getHeader(TRACE_ID_HEADER)).isEqualTo(bodyTraceId);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.instance"))
            .isEqualTo(PROBLEM_INSTANCE_PREFIX + bodyTraceId);
      }
    };
  }
}
