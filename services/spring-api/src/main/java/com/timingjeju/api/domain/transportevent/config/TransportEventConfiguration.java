package com.timingjeju.api.domain.transportevent.config;

import com.timingjeju.api.application.transportevent.TransportEventStore;
import com.timingjeju.api.application.transportevent.service.TransportEventService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TransportEventConfiguration {
  @Bean
  TransportEventService transportEventService(TransportEventStore store, Clock clock) {
    return new TransportEventService(store, clock);
  }
}
