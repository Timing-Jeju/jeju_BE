package com.timingjeju.api.documentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local",
      "app.external-api.tour-api.enabled=true",
      "app.external-api.tour-api.api-key=test-only-key",
      "app.external-api.tour-api.base-url=https://apis.data.go.kr/B551011/KorService2"
    })
@AutoConfigureMockMvc
class LocalDemoOpenApiIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void local_profile_OpenAPI에_demo_경로가_있고_POST는_request_body가_없다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/demo/imports/tour-api'].post").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/demo/imports/tour-api'].post.requestBody").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/v1/demo/storage'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/demo/storage/view'].get").exists());
  }
}
