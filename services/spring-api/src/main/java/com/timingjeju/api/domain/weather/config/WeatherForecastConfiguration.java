package com.timingjeju.api.domain.weather.config;

import com.timingjeju.api.domain.weather.ForecastBaseTimeResolver;
import com.timingjeju.api.domain.weather.KmaGridConverter;
import com.timingjeju.api.domain.weather.repository.WeatherForecastRepository;
import com.timingjeju.api.domain.weather.service.WeatherForecastQueryService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WeatherForecastConfiguration {

  @Bean
  WeatherForecastQueryService weatherForecastQueryService(
      WeatherForecastRepository repository, Clock clock) {
    return new WeatherForecastQueryService(
        repository, new KmaGridConverter(), new ForecastBaseTimeResolver(), clock);
  }
}
