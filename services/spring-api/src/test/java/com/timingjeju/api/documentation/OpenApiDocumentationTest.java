package com.timingjeju.api.documentation;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void OpenAPI_JSON에_서비스_기본_정보가_포함된다() throws Exception {
    String openApiJson =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
            .andExpect(jsonPath("$.info.title").value("Timing Jeju API"))
            .andExpect(jsonPath("$.info.version").value("v1"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    Path output = Path.of("build", "openapi", "openapi.json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, openApiJson, StandardCharsets.UTF_8);
  }

  @Test
  void OpenAPI에는_소셜_로그인_공개_API_경로와_설명이_포함된다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/auth/social/providers'].get").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/providers'].get.summary").value("소셜 로그인 공급자 조회"))
        .andExpect(jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.summary")
                .value("Naver Custom OAuth UserInfo 변환"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['200'].content['*/*'].schema.$ref")
                .value("#/components/schemas/NaverUserInfoResponse"))
        .andExpect(
            jsonPath("$.components.schemas.NaverUserInfoResponse.properties.email_verified")
                .doesNotExist())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['429']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['503']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/auth/social/providers'].get.security").isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.security").isEmpty());
  }

  @Test
  void OpenAPI에_전역_bearerAuth_보안_계약이_포함된다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
        .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
  }

  @Test
  void Swagger_UI를_조회할_수_있다() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
