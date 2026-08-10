package com.timingjeju.api.global.idempotency;

import com.timingjeju.api.application.idempotency.IdempotencyRecordStore;
import com.timingjeju.api.application.idempotency.IdempotencyTransactions;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.idempotency.TransactionalIdempotencyService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdempotencyConfiguration {

  @Bean
  Clock idempotencyClock() {
    return Clock.systemUTC();
  }

  @Bean
  IdempotencyUseCase idempotencyUseCase(
      IdempotencyRecordStore store, IdempotencyTransactions transactions, Clock idempotencyClock) {
    return new TransactionalIdempotencyService(store, transactions, idempotencyClock);
  }
}
