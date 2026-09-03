package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import com.timingjeju.api.application.datahealth.ProviderDataHealthException;
import com.timingjeju.api.application.datahealth.ProviderDataHealthItem;
import com.timingjeju.api.application.datahealth.ProviderDataHealthKey;
import com.timingjeju.api.application.datahealth.ProviderDataHealthReason;
import com.timingjeju.api.application.datahealth.ProviderDataHealthStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ExternalDataHealthEndpointTest {
  private static final Instant ATTEMPT = Instant.parse("2026-09-02T01:00:00Z");
  private static final Instant SUCCESS = Instant.parse("2026-09-02T00:30:00Z");
  private static final Instant FACTS = Instant.parse("2026-09-02T00:25:00Z");

  @Test
  void 마지막_실패와_이전_유효_facts를_함께_보이고_mobility는_DEFER_DISABLED다() {
    CompletedProviderDataHealthService service = mock(CompletedProviderDataHealthService.class);
    when(service.collect()).thenReturn(List.of(lastAttemptFailed()));

    ExternalDataHealthResponse response = new ExternalDataHealthEndpoint(service).health();

    assertThat(response.status()).isEqualTo(ExternalDataHealthOverallStatus.DOWN);
    assertThat(response.dependencies())
        .extracting(ExternalDataHealthDependency::provider)
        .containsExactly("mobility-route", "tour-api");
    ExternalDataHealthDependency tourApi = response.dependencies().get(1);
    assertThat(tourApi.status()).isEqualTo(ProviderDataHealthStatus.LAST_ATTEMPT_FAILED);
    assertThat(tourApi.lastAttemptAt()).isEqualTo(ATTEMPT);
    assertThat(tourApi.lastSuccessAt()).isEqualTo(SUCCESS);
    assertThat(tourApi.factsAsOf()).isEqualTo(FACTS);
    assertThat(tourApi.fallbackCode()).isEqualTo(ExternalDataFallbackCode.대체_미사용);

    ExternalDataHealthDependency mobility = response.dependencies().getFirst();
    assertThat(mobility.status()).isEqualTo(ProviderDataHealthStatus.DISABLED);
    assertThat(mobility.reasonCode()).isEqualTo(ProviderDataHealthReason.PROVIDER_DISABLED);
    assertThat(mobility.fallbackCode()).isEqualTo(ExternalDataFallbackCode.대체_미사용);
  }

  @Test
  void 상세_projection에는_snapshot_metadata나_비밀_원문을_담을_필드가_없다() {
    assertThat(ExternalDataHealthDependency.class.getRecordComponents())
        .extracting(component -> component.getName())
        .containsExactly(
            "provider",
            "service",
            "operation",
            "status",
            "lastAttemptAt",
            "lastSuccessAt",
            "factsAsOf",
            "stale",
            "reasonCode",
            "fallbackCode")
        .doesNotContain(
            "metadata", "key", "token", "url", "query", "scope", "rawPayload", "message");
  }

  @Test
  void typed_조회_실패는_raw_cause없이_안정_code와_DOWN으로_반환한다() {
    CompletedProviderDataHealthService service = mock(CompletedProviderDataHealthService.class);
    when(service.collect()).thenThrow(ProviderDataHealthException.unavailable());

    ExternalDataHealthResponse response = new ExternalDataHealthEndpoint(service).health();

    assertThat(response.status()).isEqualTo(ExternalDataHealthOverallStatus.DOWN);
    assertThat(response.failureCode())
        .isEqualTo(ExternalDataHealthFailureCode.DATA_HEALTH_UNAVAILABLE);
    assertThat(response.dependencies())
        .extracting(ExternalDataHealthDependency::provider)
        .containsExactly("mobility-route");
  }

  private static ProviderDataHealthItem lastAttemptFailed() {
    return new ProviderDataHealthItem(
        new ProviderDataHealthKey("tour-api", "KorService2", "areaBasedSyncList2"),
        ProviderDataHealthStatus.LAST_ATTEMPT_FAILED,
        ATTEMPT,
        SUCCESS,
        FACTS,
        false,
        ProviderDataHealthReason.LATEST_RUN_FAILED);
  }
}
