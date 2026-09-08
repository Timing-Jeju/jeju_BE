package com.timingjeju.api.domain.weather.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastBaseTimeResolver;
import com.timingjeju.api.domain.weather.ForecastType;
import com.timingjeju.api.domain.weather.KmaGridConverter;
import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.dto.request.WeatherForecastQuery;
import com.timingjeju.api.domain.weather.dto.response.WeatherForecastResponse;
import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import com.timingjeju.api.domain.weather.model.SupportedWeatherGrid;
import com.timingjeju.api.domain.weather.model.WeatherForecastLookup;
import com.timingjeju.api.domain.weather.model.WeatherForecastSnapshot;
import com.timingjeju.api.domain.weather.repository.WeatherForecastRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class WeatherForecastQueryServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-03T05:20:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
  private static final KmaGridPoint JEJU_GRID = new KmaGridPoint(60, 37);

  @Test
  void injected_clock으로_grid와_latest_eligible_base를_선택한다() {
    WeatherForecastRepository repository = repositoryWithJejuGrid();
    when(repository.find(any()))
        .thenReturn(Optional.of(snapshot(base(13, 30), NOW.plusSeconds(1))));
    WeatherForecastQueryService service = service(repository);

    WeatherForecastResponse response = service.forecast(query("2026-08-03T15:00:00+09:00"));

    ArgumentCaptor<WeatherForecastLookup> lookup =
        ArgumentCaptor.forClass(WeatherForecastLookup.class);
    verify(repository).find(lookup.capture());
    assertThat(lookup.getValue().gridPoint()).isEqualTo(JEJU_GRID);
    assertThat(lookup.getValue().forecastType()).isEqualTo(ForecastType.ULTRA_SHORT);
    assertThat(lookup.getValue().base()).isEqualTo(base(13, 30));
    assertThat(response.grid().nx()).isEqualTo(60);
    assertThat(response.grid().ny()).isEqualTo(37);
    assertThat(response.fallbackUsed()).isFalse();
  }

  @Test
  void latest가_없으면_직전_base_한번만_fallback하고_stale_true를_강제한다() {
    WeatherForecastRepository repository = repositoryWithJejuGrid();
    when(repository.find(any()))
        .thenReturn(Optional.empty(), Optional.of(snapshot(base(12, 30), NOW.plusSeconds(600))));
    WeatherForecastQueryService service = service(repository);

    WeatherForecastResponse response = service.forecast(query("2026-08-03T15:00:00+09:00"));

    ArgumentCaptor<WeatherForecastLookup> lookup =
        ArgumentCaptor.forClass(WeatherForecastLookup.class);
    verify(repository, org.mockito.Mockito.times(2)).find(lookup.capture());
    assertThat(lookup.getAllValues())
        .extracting(WeatherForecastLookup::base)
        .containsExactly(base(13, 30), base(12, 30));
    assertThat(response.fallbackUsed()).isTrue();
    assertThat(response.stale()).isTrue();
  }

  @Test
  void evaluatedAt과_expiresAt이_같으면_stale이다() {
    WeatherForecastRepository repository = repositoryWithJejuGrid();
    when(repository.find(any())).thenReturn(Optional.of(snapshot(base(13, 30), NOW)));

    WeatherForecastResponse response =
        service(repository).forecast(query("2026-08-03T15:00:00+09:00"));

    assertThat(response.stale()).isTrue();
    assertThat(response.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-08-03T14:20:00+09:00"));
  }

  @Test
  void 평가시각을_한번만_캡처해_type_base_horizon_stale에_동일하게_사용한다() {
    Instant beforePublication = Instant.parse("2026-08-15T17:09:59.999Z");
    StepClock clock = new StepClock(beforePublication, Instant.parse("2026-08-15T17:10:00Z"));
    WeatherForecastRepository repository = repositoryWithJejuGrid();
    when(repository.find(any()))
        .thenReturn(
            Optional.of(
                new WeatherForecastSnapshot(
                    ForecastType.VILLAGE,
                    new ForecastBaseTime(LocalDate.of(2026, 8, 15), LocalTime.of(23, 0)),
                    Instant.parse("2026-08-15T14:00:00Z"),
                    Instant.parse("2026-08-16T01:00:00Z"),
                    new BigDecimal("27.5"),
                    10,
                    null,
                    "0",
                    "1",
                    70,
                    new BigDecimal("2.1"),
                    Instant.parse("2026-08-15T17:00:00Z"),
                    beforePublication)));
    WeatherForecastQueryService service =
        new WeatherForecastQueryService(
            repository, new KmaGridConverter(), new ForecastBaseTimeResolver(), clock);

    WeatherForecastResponse response = service.forecast(query("2026-08-16T10:00:00+09:00"));

    ArgumentCaptor<WeatherForecastLookup> lookup =
        ArgumentCaptor.forClass(WeatherForecastLookup.class);
    verify(repository).find(lookup.capture());
    assertThat(lookup.getValue().forecastType()).isEqualTo(ForecastType.VILLAGE);
    assertThat(lookup.getValue().base()).isEqualTo(baseOn("2026-08-15", 23, 0));
    assertThat(response.stale()).isTrue();
    assertThat(clock.calls()).isEqualTo(1);
  }

  @Test
  void 지원하지_않는_제주_grid와_horizon은_서로_다른_typed_422다() {
    WeatherForecastRepository repository = mock(WeatherForecastRepository.class);
    when(repository.findRegionGrid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service(repository).forecast(query("2026-08-03T15:00:00+09:00")))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("WEATHER_LOCATION_NOT_SUPPORTED");

    assertThatThrownBy(
            () -> service(repositoryWithJejuGrid()).forecast(query("2026-08-13T15:00:00+09:00")))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("WEATHER_FORECAST_HORIZON_NOT_SUPPORTED");
  }

  @Test
  void current와_previous가_모두_없으면_typed_503이고_programmer_bug는_숨기지_않는다() {
    WeatherForecastRepository unavailable = repositoryWithJejuGrid();
    when(unavailable.find(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service(unavailable).forecast(query("2026-08-03T15:00:00+09:00")))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("WEATHER_FORECAST_UNAVAILABLE");

    WeatherForecastRepository broken = repositoryWithJejuGrid();
    when(broken.find(any())).thenThrow(new IllegalStateException("programmer bug"));
    assertThatThrownBy(() -> service(broken).forecast(query("2026-08-03T15:00:00+09:00")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("programmer bug");
  }

  @Test
  void 계획_선택자는_미인증을_조회없이_거부하고_타_소유자를_404로_은닉한다() {
    WeatherForecastRepository repository = mock(WeatherForecastRepository.class);
    java.util.UUID item = java.util.UUID.randomUUID();
    java.util.UUID owner = java.util.UUID.randomUUID();
    var query =
        WeatherForecastQuery.parse(null, null, item.toString(), "2026-08-03T15:00:00+09:00");
    assertThatThrownBy(() -> service(repository).forecast(query))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("AUTHENTICATION_REQUIRED");
    org.mockito.Mockito.verifyNoInteractions(repository);
    assertThatThrownBy(() -> service(repository).forecast(query, owner))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("WEATHER_REFERENCE_NOT_FOUND");
    verify(repository).findOwnedTripItem(item, owner);
    verify(repository, org.mockito.Mockito.never()).find(any());
  }

  @Test
  void 공개_장소와_소유_계획은_공개_좌표만_grid로_변환한다() {
    for (boolean planned : new boolean[] {false, true}) {
      WeatherForecastRepository repository = mock(WeatherForecastRepository.class);
      java.util.UUID id = java.util.UUID.randomUUID();
      java.util.UUID owner = java.util.UUID.randomUUID();
      var anchor =
          Optional.of(
              new com.timingjeju.api.domain.weather.model.PublicWeatherAnchor(
                  33.458111, 126.941516));
      if (planned) {
        when(repository.findOwnedTripItem(id, owner)).thenReturn(anchor);
      } else {
        when(repository.findPublicPlace(id)).thenReturn(anchor);
      }
      when(repository.findSupportedGrid(JEJU_GRID))
          .thenReturn(Optional.of(new SupportedWeatherGrid(JEJU_GRID, "성산")));
      when(repository.find(any()))
          .thenReturn(Optional.of(snapshot(base(13, 30), NOW.plusSeconds(60))));
      var query =
          WeatherForecastQuery.parse(
              null,
              planned ? null : id.toString(),
              planned ? id.toString() : null,
              "2026-08-03T15:00:00+09:00");
      assertThat(service(repository).forecast(query, owner).grid().nx()).isEqualTo(60);
      verify(repository).findSupportedGrid(JEJU_GRID);
    }
  }

  private static WeatherForecastQueryService service(WeatherForecastRepository repository) {
    return new WeatherForecastQueryService(
        repository, new KmaGridConverter(), new ForecastBaseTimeResolver(), CLOCK);
  }

  private static WeatherForecastRepository repositoryWithJejuGrid() {
    WeatherForecastRepository repository = mock(WeatherForecastRepository.class);
    when(repository.findRegionGrid(any()))
        .thenReturn(Optional.of(new SupportedWeatherGrid(JEJU_GRID, "제주 동부")));
    return repository;
  }

  private static WeatherForecastQuery query(String dateTime) {
    return WeatherForecastQuery.parse("seongsan", null, null, dateTime);
  }

  private static ForecastBaseTime base(int hour, int minute) {
    return new ForecastBaseTime(LocalDate.of(2026, 8, 3), LocalTime.of(hour, minute));
  }

  private static ForecastBaseTime baseOn(String date, int hour, int minute) {
    return new ForecastBaseTime(LocalDate.parse(date), LocalTime.of(hour, minute));
  }

  private static final class StepClock extends Clock {
    private final Instant[] instants;
    private final AtomicInteger calls = new AtomicInteger();

    private StepClock(Instant... instants) {
      this.instants = instants;
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
      int index = calls.getAndIncrement();
      return instants[Math.min(index, instants.length - 1)];
    }

    private int calls() {
      return calls.get();
    }
  }

  private static WeatherForecastSnapshot snapshot(ForecastBaseTime base, Instant expiresAt) {
    return new WeatherForecastSnapshot(
        ForecastType.ULTRA_SHORT,
        base,
        base.baseDate().atTime(base.baseTime()).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        Instant.parse("2026-08-03T06:00:00Z"),
        new BigDecimal("27.5"),
        null,
        new BigDecimal("0.0"),
        "0",
        null,
        70,
        new BigDecimal("2.1"),
        Instant.parse("2026-08-03T05:10:00Z"),
        expiresAt);
  }
}
