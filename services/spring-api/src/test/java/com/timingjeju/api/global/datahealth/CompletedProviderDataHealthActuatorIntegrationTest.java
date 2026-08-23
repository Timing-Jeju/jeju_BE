package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import com.timingjeju.api.application.datahealth.ProviderDataHealthException;
import com.timingjeju.api.application.datahealth.ProviderDataHealthItem;
import com.timingjeju.api.application.datahealth.ProviderDataHealthKey;
import com.timingjeju.api.application.datahealth.ProviderDataHealthReason;
import com.timingjeju.api.application.datahealth.ProviderDataHealthStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class CompletedProviderDataHealthActuatorIntegrationTest {
  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  class DefaultDisabled {
    @Autowired private MockMvc mvc;

    @Test
    void 기본_비활성은_기존_200_UP_status_only를_유지한다() throws Exception {
      MvcResult result =
          mvc.perform(get("/actuator/health"))
              .andExpectAll(
                  status().isOk(),
                  content()
                      .contentTypeCompatibleWith("application/vnd.spring-boot.actuator.v3+json"),
                  jsonPath("$.status").value("UP"),
                  jsonPath("$.components").doesNotExist(),
                  jsonPath("$.details").doesNotExist())
              .andReturn();

      assertNoProviderDetails(result);
    }
  }

  @Nested
  @SpringBootTest(properties = "app.data-health.actuator.enabled=true")
  @AutoConfigureMockMvc
  class Enabled {
    @Autowired private MockMvc mvc;
    @MockitoBean private CompletedProviderDataHealthService service;

    @Test
    void healthy는_200_UP_status_only다() throws Exception {
      when(service.collect()).thenReturn(List.of(fresh()));

      MvcResult result =
          mvc.perform(get("/actuator/health"))
              .andExpectAll(
                  status().isOk(),
                  jsonPath("$.status").value("UP"),
                  jsonPath("$.components").doesNotExist(),
                  jsonPath("$.details").doesNotExist())
              .andReturn();

      assertNoProviderDetails(result);
    }

    @Test
    void unhealthy와_typed_unavailable은_표준_503_DOWN_status_only다() throws Exception {
      when(service.collect()).thenReturn(List.of(neverSynced()));
      assertDownOnly();

      when(service.collect()).thenThrow(ProviderDataHealthException.unavailable());
      assertDownOnly();
    }

    private void assertDownOnly() throws Exception {
      MvcResult result =
          mvc.perform(get("/actuator/health"))
              .andExpectAll(
                  status().isServiceUnavailable(),
                  jsonPath("$.status").value("DOWN"),
                  jsonPath("$.components").doesNotExist(),
                  jsonPath("$.details").doesNotExist())
              .andReturn();

      assertNoProviderDetails(result);
    }
  }

  private static ProviderDataHealthItem fresh() {
    Instant now = Instant.parse("2026-08-24T12:00:00Z");
    return new ProviderDataHealthItem(
        key(),
        ProviderDataHealthStatus.FRESH,
        now,
        now,
        now.minusSeconds(1),
        false,
        ProviderDataHealthReason.HEALTHY);
  }

  private static ProviderDataHealthItem neverSynced() {
    return new ProviderDataHealthItem(
        key(),
        ProviderDataHealthStatus.NEVER_SYNCED,
        null,
        null,
        null,
        false,
        ProviderDataHealthReason.NO_SUCCESSFUL_IMPORT);
  }

  private static ProviderDataHealthKey key() {
    return new ProviderDataHealthKey("tour-api", "KorService2", "areaBasedSyncList2");
  }

  private static void assertNoProviderDetails(MvcResult result) throws Exception {
    assertThat(result.getResponse().getContentAsString().toLowerCase())
        .doesNotContain(
            "provider",
            "service",
            "operation",
            "count",
            "timestamp",
            "stale",
            "reason",
            "key",
            "token",
            "url",
            "query",
            "scope",
            "raw",
            "sql",
            "exception",
            "components",
            "details");
  }
}
