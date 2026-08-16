-- Issue #76: preserve the KMA village forecast publication version on normalized facts.

alter table public.weather_forecasts
  add column forecast_version text;

alter table public.weather_forecasts
  add constraint ck_weather_forecasts_version_by_type
    check (
      (forecast_type = 'short'
        and forecast_version is not null
        and forecast_version ~ '^[0-9]{12}$')
      or (forecast_type = 'ultra_short' and forecast_version is null)
    ) not valid;

comment on column public.weather_forecasts.forecast_version is
  'KMA getFcstVersion SHRT file version (yyyyMMddHHmm); required for new short forecasts';
