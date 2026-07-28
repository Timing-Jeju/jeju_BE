package com.timingjeju.api.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  @Bean
  OpenAPI timingJejuOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Timing Jeju API")
                .description("제주 여행 일정과 관광·교통 데이터를 제공하는 공개 API")
                .version("v1"));
  }
}
