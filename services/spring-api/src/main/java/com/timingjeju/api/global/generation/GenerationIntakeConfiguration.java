package com.timingjeju.api.global.generation;

import com.timingjeju.api.application.generation.GenerationIntakeStore;
import com.timingjeju.api.application.generation.service.GenerationIntakeService;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class GenerationIntakeConfiguration {
  @Bean
  GenerationIntakeService generationIntakeService(GenerationIntakeStore store, Clock clock) {
    return new GenerationIntakeService(store, clock);
  }
}
