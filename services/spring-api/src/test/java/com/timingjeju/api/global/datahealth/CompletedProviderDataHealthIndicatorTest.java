package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import com.timingjeju.api.application.datahealth.ProviderDataHealthException;
import com.timingjeju.api.application.datahealth.ProviderDataHealthItem;
import com.timingjeju.api.application.datahealth.ProviderDataHealthKey;
import com.timingjeju.api.application.datahealth.ProviderDataHealthReason;
import com.timingjeju.api.application.datahealth.ProviderDataHealthStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class CompletedProviderDataHealthIndicatorTest {
  private static final ProviderDataHealthKey KEY =
      new ProviderDataHealthKey("tour-api", "KorService2", "areaBasedSyncList2");
  private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

  @Test
  void FRESH와_DISABLED만_있거나_전체_DISABLED면_UP이고_details는_비어_있다() {
    CompletedProviderDataHealthService service = mock(CompletedProviderDataHealthService.class);
    CompletedProviderDataHealthIndicator indicator =
        new CompletedProviderDataHealthIndicator(service);
    when(service.collect()).thenReturn(List.of(fresh(), disabled()));

    Health mixed = indicator.health();

    assertThat(mixed.getStatus()).isEqualTo(Status.UP);
    assertThat(mixed.getDetails()).isEmpty();
    verify(service, times(1)).collect();

    when(service.collect()).thenReturn(List.of(disabled(), disabled()));
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @ParameterizedTest
  @EnumSource(
      value = ProviderDataHealthStatus.class,
      names = {"NEVER_SYNCED", "NO_RECENT_VALID_FACTS", "STALE", "LAST_ATTEMPT_FAILED"})
  void unhealthy_status가_하나라도_있으면_DOWN이고_details는_비어_있다(ProviderDataHealthStatus status) {
    CompletedProviderDataHealthService service = mock(CompletedProviderDataHealthService.class);
    when(service.collect()).thenReturn(List.of(fresh(), unhealthy(status)));

    Health health = new CompletedProviderDataHealthIndicator(service).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).isEmpty();
    verify(service, times(1)).collect();
  }

  @Test
  void typed_DATA_HEALTH_UNAVAILABLE만_raw_cause없이_DOWN으로_변환한다() {
    CompletedProviderDataHealthService service = mock(CompletedProviderDataHealthService.class);
    when(service.collect()).thenThrow(ProviderDataHealthException.unavailable());

    Health health = new CompletedProviderDataHealthIndicator(service).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).isEmpty();
    verify(service, times(1)).collect();
  }

  @Test
  void programmer_exception은_DOWN으로_숨기지_않고_원형_전파한다() {
    CompletedProviderDataHealthService service = mock(CompletedProviderDataHealthService.class);
    IllegalStateException programmerBug = new IllegalStateException("programmer-bug");
    when(service.collect()).thenThrow(programmerBug);

    assertThatThrownBy(() -> new CompletedProviderDataHealthIndicator(service).health())
        .isSameAs(programmerBug);
  }

  private static ProviderDataHealthItem fresh() {
    return new ProviderDataHealthItem(
        KEY,
        ProviderDataHealthStatus.FRESH,
        NOW,
        NOW,
        NOW.minusSeconds(1),
        false,
        ProviderDataHealthReason.HEALTHY);
  }

  private static ProviderDataHealthItem disabled() {
    return new ProviderDataHealthItem(
        KEY,
        ProviderDataHealthStatus.DISABLED,
        null,
        null,
        null,
        false,
        ProviderDataHealthReason.PROVIDER_DISABLED);
  }

  private static ProviderDataHealthItem unhealthy(ProviderDataHealthStatus status) {
    return switch (status) {
      case NEVER_SYNCED ->
          new ProviderDataHealthItem(
              KEY, status, null, null, null, false, ProviderDataHealthReason.NO_SUCCESSFUL_IMPORT);
      case NO_RECENT_VALID_FACTS ->
          new ProviderDataHealthItem(
              KEY,
              status,
              NOW,
              null,
              null,
              false,
              ProviderDataHealthReason.VALID_FACTS_WINDOW_EXHAUSTED);
      case STALE ->
          new ProviderDataHealthItem(
              KEY,
              status,
              NOW,
              NOW,
              NOW.minusSeconds(1),
              true,
              ProviderDataHealthReason.TTL_EXPIRED);
      case LAST_ATTEMPT_FAILED ->
          new ProviderDataHealthItem(
              KEY,
              status,
              NOW,
              NOW.minusSeconds(1),
              NOW.minusSeconds(2),
              false,
              ProviderDataHealthReason.LATEST_RUN_FAILED);
      default -> throw new IllegalArgumentException("unhealthy status만 허용합니다.");
    };
  }
}
