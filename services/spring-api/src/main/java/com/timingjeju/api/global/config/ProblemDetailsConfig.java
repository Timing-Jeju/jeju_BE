package com.timingjeju.api.global.config;

import com.timingjeju.api.global.error.ProblemCodeRegistry;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import com.timingjeju.api.global.error.ValidationErrorMapper;
import com.timingjeju.api.global.logging.RequestTraceId;
import com.timingjeju.api.global.logging.RequestTraceIdFilter;
import com.timingjeju.api.global.logging.TraceIdGenerator;
import com.timingjeju.api.global.logging.UuidTraceIdGenerator;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ProblemDetailsConfig {

  @Bean
  TraceIdGenerator traceIdGenerator() {
    return new UuidTraceIdGenerator();
  }

  @Bean
  RequestTraceId requestTraceId(TraceIdGenerator traceIdGenerator) {
    return new RequestTraceId(traceIdGenerator);
  }

  @Bean
  RequestTraceIdFilter requestTraceIdFilter(RequestTraceId requestTraceId) {
    return new RequestTraceIdFilter(requestTraceId);
  }

  @Bean
  ProblemCodeRegistry problemCodeRegistry(List<ProblemDefinitionContributor> contributors) {
    return new ProblemCodeRegistry(contributors);
  }

  @Bean
  ProblemResponseWriter problemResponseWriter(
      ObjectMapper objectMapper, ProblemCodeRegistry registry, RequestTraceId requestTraceId) {
    return new ProblemResponseWriter(objectMapper, registry, requestTraceId);
  }

  @Bean
  ValidationErrorMapper validationErrorMapper() {
    return new ValidationErrorMapper();
  }
}
