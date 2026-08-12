package com.timingjeju.api.global.externalapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class ExternalApiActuatorIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void actuator_info는_provider_활성_여부만_공개한다() throws Exception {
    mockMvc
        .perform(get("/actuator/info"))
        .andExpectAll(
            status().isOk(),
            jsonPath("$.externalApis.tourApi").value(false),
            jsonPath("$.externalApis.tago").value(false),
            jsonPath("$.externalApis.tmap").value(false),
            jsonPath("$.externalApis.kma").value(false),
            content().string(not(containsString("apiKey"))),
            content().string(not(containsString("baseUrl"))),
            content().string(not(containsString("timeout"))));
  }
}
