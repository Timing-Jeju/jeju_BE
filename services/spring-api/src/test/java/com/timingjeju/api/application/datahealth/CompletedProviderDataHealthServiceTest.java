package com.timingjeju.api.application.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CompletedProviderDataHealthServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
  private static final ProviderDataHealthKey TOUR_API =
      new ProviderDataHealthKey("tour-api", "KorService2", "areaBasedSyncList2");
  private static final ProviderDataHealthKey TAGO =
      new ProviderDataHealthKey(
          "TAGO", "ArvlInfoInqireService", "getSttnAcctoArvlPrearngeInfoList");

  @Test
  void enabled_operation의_성공_이력이_없으면_NEVER_SYNCED다() {
    ProviderDataHealthItem item =
        ProviderDataHealthEvaluator.evaluate(policy(TOUR_API, true), null, NOW);

    assertThat(item.key()).isEqualTo(TOUR_API);
    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.NEVER_SYNCED);
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.NO_SUCCESSFUL_IMPORT);
    assertThat(item.lastAttemptAt()).isNull();
    assertThat(item.lastSuccessAt()).isNull();
    assertThat(item.factsAsOf()).isNull();
    assertThat(item.stale()).isFalse();
  }

  @Test
  void failed_attempt만_있으면_NEVER_SYNCED이지만_lastAttemptAt은_보존한다() {
    ProviderDataHealthHistory history =
        history(TOUR_API, NOW.minusSeconds(10), ProviderDataHealthAttemptStatus.FAILED, null, null);

    ProviderDataHealthItem item =
        ProviderDataHealthEvaluator.evaluate(policy(TOUR_API, true), history, NOW);

    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.NO_RECENT_VALID_FACTS);
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.VALID_FACTS_WINDOW_EXHAUSTED);
    assertThat(item.lastAttemptAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(item.lastSuccessAt()).isNull();
    assertThat(item.factsAsOf()).isNull();
  }

  @Test
  void terminal_history는_있지만_최근_window에_valid_facts가_없으면_NO_RECENT다() {
    ProviderDataHealthHistory history =
        history(
            TOUR_API, NOW.minusSeconds(10), ProviderDataHealthAttemptStatus.SUCCEEDED, null, null);

    ProviderDataHealthItem item =
        ProviderDataHealthEvaluator.evaluate(policy(TOUR_API, true), history, NOW);

    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.NO_RECENT_VALID_FACTS);
    assertThat(item.lastAttemptAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(item.lastSuccessAt()).isNull();
    assertThat(item.factsAsOf()).isNull();
    assertThat(item.stale()).isFalse();
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.VALID_FACTS_WINDOW_EXHAUSTED);
  }

  @Test
  void NO_RECENT는_attempt가_없거나_facts가_있으면_거부한다() {
    assertThatThrownBy(
            () ->
                new ProviderDataHealthItem(
                    TOUR_API,
                    ProviderDataHealthStatus.NO_RECENT_VALID_FACTS,
                    null,
                    null,
                    null,
                    false,
                    ProviderDataHealthReason.VALID_FACTS_WINDOW_EXHAUSTED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ProviderDataHealthItem(
                    TOUR_API,
                    ProviderDataHealthStatus.NO_RECENT_VALID_FACTS,
                    NOW,
                    NOW.minusSeconds(1),
                    NOW.minusSeconds(2),
                    false,
                    ProviderDataHealthReason.VALID_FACTS_WINDOW_EXHAUSTED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void NEVER_SYNCED는_lastAttemptAt도_null이어야_한다() {
    assertThatThrownBy(
            () ->
                new ProviderDataHealthItem(
                    TOUR_API,
                    ProviderDataHealthStatus.NEVER_SYNCED,
                    NOW.minusSeconds(1),
                    null,
                    null,
                    false,
                    ProviderDataHealthReason.NO_SUCCESSFUL_IMPORT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 마지막_success_facts가_TTL_경계보다_뒤면_FRESH다() {
    ProviderDataHealthPolicy policy = policy(TOUR_API, true);
    ProviderDataHealthHistory history =
        history(
            TOUR_API,
            NOW.minusSeconds(30),
            ProviderDataHealthAttemptStatus.SUCCEEDED,
            NOW.minusSeconds(30),
            NOW.minus(policy.ttl()).plusNanos(1));

    ProviderDataHealthItem item = ProviderDataHealthEvaluator.evaluate(policy, history, NOW);

    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.FRESH);
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.HEALTHY);
    assertThat(item.stale()).isFalse();
  }

  @Test
  void facts_TTL이_평가시각과_정확히_같으면_STALE다() {
    ProviderDataHealthPolicy policy = policy(TOUR_API, true);
    ProviderDataHealthHistory history =
        history(
            TOUR_API,
            NOW.minusSeconds(60),
            ProviderDataHealthAttemptStatus.SUCCEEDED,
            NOW.minusSeconds(60),
            NOW.minus(policy.ttl()));

    ProviderDataHealthItem item = ProviderDataHealthEvaluator.evaluate(policy, history, NOW);

    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.STALE);
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.TTL_EXPIRED);
    assertThat(item.stale()).isTrue();
  }

  @Test
  void 최신_attempt가_failed여도_이전_success_facts를_함께_보존한다() {
    ProviderDataHealthHistory history =
        history(
            TAGO,
            NOW.minusSeconds(10),
            ProviderDataHealthAttemptStatus.FAILED,
            NOW.minusSeconds(40),
            NOW.minusSeconds(45));

    ProviderDataHealthItem item =
        ProviderDataHealthEvaluator.evaluate(policy(TAGO, true), history, NOW);

    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.LAST_ATTEMPT_FAILED);
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.LATEST_RUN_FAILED);
    assertThat(item.lastAttemptAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(item.lastSuccessAt()).isEqualTo(NOW.minusSeconds(40));
    assertThat(item.factsAsOf()).isEqualTo(NOW.minusSeconds(45));
    assertThat(item.stale()).isFalse();
  }

  @Test
  void 최신_lifecycle은_succeeded지만_valid_facts가_이전_run이면_그_facts의_TTL로_평가한다() {
    ProviderDataHealthPolicy policy = policy(TOUR_API, true);
    ProviderDataHealthHistory history =
        history(
            TOUR_API,
            NOW.minusSeconds(10),
            ProviderDataHealthAttemptStatus.SUCCEEDED,
            NOW.minusSeconds(60),
            NOW.minusSeconds(90));

    ProviderDataHealthItem item = ProviderDataHealthEvaluator.evaluate(policy, history, NOW);

    assertThat(item.lastAttemptAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(item.lastSuccessAt()).isEqualTo(NOW.minusSeconds(60));
    assertThat(item.factsAsOf()).isEqualTo(NOW.minusSeconds(90));
    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.FRESH);
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.HEALTHY);
  }

  @Test
  void valid_facts_null_pair와_시간_역전은_계속_거부한다() {
    assertThatThrownBy(
            () ->
                history(
                    TOUR_API,
                    NOW.minusSeconds(10),
                    ProviderDataHealthAttemptStatus.SUCCEEDED,
                    NOW.minusSeconds(60),
                    null))
        .isInstanceOf(ProviderDataHealthException.class);
    assertThatThrownBy(
            () ->
                history(
                    TOUR_API,
                    NOW.minusSeconds(60),
                    ProviderDataHealthAttemptStatus.SUCCEEDED,
                    NOW.minusSeconds(10),
                    NOW.minusSeconds(20)))
        .isInstanceOf(ProviderDataHealthException.class);
  }

  @Test
  void disabled_operation은_DB_history와_무관하게_DISABLED다() {
    ProviderDataHealthItem item =
        ProviderDataHealthEvaluator.evaluate(policy(TAGO, false), null, NOW);

    assertThat(item.status()).isEqualTo(ProviderDataHealthStatus.DISABLED);
    assertThat(item.reasonCode()).isEqualTo(ProviderDataHealthReason.PROVIDER_DISABLED);
    assertThat(item.lastAttemptAt()).isNull();
    assertThat(item.stale()).isFalse();
  }

  @Test
  void 한_collect에서_Clock을_정확히_한번만_캡처한다() {
    CountingClock clock = new CountingClock(NOW);
    CompletedProviderDataHealthSettings settings =
        CompletedProviderDataHealthCatalog.settings(true, true, true);
    List<ProviderDataHealthHistory> histories =
        settings.policies().stream()
            .map(
                policy ->
                    history(
                        policy.key(),
                        NOW.minusSeconds(1),
                        ProviderDataHealthAttemptStatus.SUCCEEDED,
                        NOW.minusSeconds(1),
                        NOW.minus(policy.ttl())))
            .toList();
    CompletedProviderDataHealthService service =
        new CompletedProviderDataHealthService(keys -> histories, clock, settings);

    assertThat(service.collect()).allMatch(ProviderDataHealthItem::stale);
    assertThat(clock.calls()).isOne();
  }

  @Test
  void 반환값은_provider_service_operation_정렬이며_list와_item이_immutable이다() {
    CompletedProviderDataHealthSettings settings =
        new CompletedProviderDataHealthSettings(
            CompletedProviderDataHealthCatalog.policies().reversed());
    List<ProviderDataHealthItem> result =
        new CompletedProviderDataHealthService(
                keys -> List.of(), Clock.fixed(NOW, ZoneOffset.UTC), settings)
            .collect();

    assertThat(result)
        .extracting(ProviderDataHealthItem::key)
        .containsExactlyElementsOf(CompletedProviderDataHealthCatalog.keys());
    assertThatThrownBy(() -> result.add(result.getFirst()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(ProviderDataHealthItem.class.isRecord()).isTrue();
  }

  @Test
  void TTL은_양수이고_24시간을_넘을_수_없다() {
    assertThatThrownBy(() -> new ProviderDataHealthPolicy(TOUR_API, Duration.ZERO, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ProviderDataHealthPolicy(TOUR_API, Duration.ofHours(24).plusNanos(1), true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonical_catalog는_공급자별_enabled_config를_정확히_적용한다() {
    assertThat(CompletedProviderDataHealthCatalog.policies(false, true, false))
        .allSatisfy(
            policy ->
                assertThat(policy.enabled()).isEqualTo(policy.key().provider().equals("TAGO")));
  }

  @Test
  void production_settings는_canonical_8개가_하나라도_누락되면_fail_fast한다() {
    List<ProviderDataHealthPolicy> missing =
        CompletedProviderDataHealthCatalog.policies().subList(0, 7);

    assertThatThrownBy(() -> new CompletedProviderDataHealthSettings(missing))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void production_settings는_extra나_mobility_key를_fail_fast한다() {
    List<ProviderDataHealthPolicy> extra =
        new ArrayList<>(CompletedProviderDataHealthCatalog.policies());
    extra.add(
        new ProviderDataHealthPolicy(
            new ProviderDataHealthKey("tmap", "routes", "transitRoute"),
            Duration.ofMinutes(5),
            true));

    assertThatThrownBy(() -> new CompletedProviderDataHealthSettings(extra))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ProviderDataHealthPolicy policy(ProviderDataHealthKey key, boolean enabled) {
    return new ProviderDataHealthPolicy(key, Duration.ofMinutes(2), enabled);
  }

  private static ProviderDataHealthHistory history(
      ProviderDataHealthKey key,
      Instant lastAttemptAt,
      ProviderDataHealthAttemptStatus latestStatus,
      Instant lastSuccessAt,
      Instant factsAsOf) {
    return new ProviderDataHealthHistory(
        key, lastAttemptAt, latestStatus, lastSuccessAt, factsAsOf);
  }

  private static final class CountingClock extends Clock {
    private final Instant instant;
    private final AtomicInteger calls = new AtomicInteger();

    private CountingClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      calls.incrementAndGet();
      return instant;
    }

    private int calls() {
      return calls.get();
    }
  }
}
