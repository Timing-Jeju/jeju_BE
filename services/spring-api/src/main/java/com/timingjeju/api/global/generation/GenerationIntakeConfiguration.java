package com.timingjeju.api.global.generation;

import com.timingjeju.api.application.generation.GenerationIntakeStore;
import com.timingjeju.api.application.generation.service.GenerationIntakeService;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class GenerationIntakeConfiguration {
  @Bean
  com.timingjeju.api.application.generation.service.GenerationApplyService generationApplyService(
      com.timingjeju.api.application.generation.GenerationApplyStore store, Clock clock) {
    return new com.timingjeju.api.application.generation.service.GenerationApplyService(
        store, clock);
  }

  @Bean
  GenerationIntakeService generationIntakeService(GenerationIntakeStore store, Clock clock) {
    return new GenerationIntakeService(store, clock);
  }

  @Bean
  com.timingjeju.api.application.generation.service.GenerationQueryService generationQueryService(
      com.timingjeju.api.application.generation.GenerationRunReader reader, Clock clock) {
    return new com.timingjeju.api.application.generation.service.GenerationQueryService(
        reader, clock);
  }
}
