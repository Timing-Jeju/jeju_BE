-- Issue #76: preserve the KMA village forecast publication version on normalized facts.

alter table public.weather_forecasts
  add column forecast_version text,
  add column precipitation_intensity_code smallint,
  add column wind_strength_code smallint;

alter table public.weather_forecasts
  add constraint ck_weather_forecasts_version_by_type
    check (
      (forecast_type = 'short'
        and forecast_version is not null
        and forecast_version ~ '^[0-9]{12}$')
      or (forecast_type = 'ultra_short' and forecast_version is null)
    ) not valid;

alter table public.weather_forecasts
  add constraint ck_weather_forecasts_qualitative_precipitation
    check (
      precipitation_intensity_code is null
      or (forecast_type = 'short'
        and precipitation_intensity_code between 0 and 3
        and precipitation_amount_mm is null)
    ) not valid,
  add constraint ck_weather_forecasts_qualitative_wind
    check (
      wind_strength_code is null
      or (forecast_type = 'short'
        and wind_strength_code between 1 and 3
        and wind_speed_mps is null)
    ) not valid;

comment on column public.weather_forecasts.forecast_version is
  'KMA getFcstVersion SHRT file version (yyyyMMddHHmm); required for new short forecasts';

comment on column public.weather_forecasts.precipitation_intensity_code is
  'KMA extended-horizon PCP qualitative code; never millimeters';

comment on column public.weather_forecasts.wind_strength_code is
  'KMA extended-horizon WSD qualitative code; never meters per second';
