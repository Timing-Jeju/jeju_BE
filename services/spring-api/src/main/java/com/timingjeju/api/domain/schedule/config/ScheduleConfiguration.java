package com.timingjeju.api.domain.schedule.config;

import com.timingjeju.api.application.schedule.ScheduleMutationStore;
import com.timingjeju.api.application.schedule.ScheduleStore;
import com.timingjeju.api.application.schedule.service.ScheduleMutationService;
import com.timingjeju.api.application.schedule.service.ScheduleQueryService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ScheduleConfiguration {
  @Bean
  ScheduleQueryService scheduleQueryService(ScheduleStore schedules, Clock clock) {
    return new ScheduleQueryService(schedules, clock);
  }

  @Bean
  ScheduleMutationService scheduleMutationService(ScheduleMutationStore schedules, Clock clock) {
    return new ScheduleMutationService(schedules, clock);
  }
}
