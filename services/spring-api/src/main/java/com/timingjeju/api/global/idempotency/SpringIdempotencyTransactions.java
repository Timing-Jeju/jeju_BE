package com.timingjeju.api.global.idempotency;

import com.timingjeju.api.application.idempotency.IdempotencyTransactions;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class SpringIdempotencyTransactions implements IdempotencyTransactions {

  private final TransactionTemplate required;
  private final TransactionTemplate requiresNew;

  public SpringIdempotencyTransactions(PlatformTransactionManager transactionManager) {
    this.required = new TransactionTemplate(transactionManager);
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public <T> T requiresNew(Supplier<T> work) {
    return requiresNew.execute(status -> work.get());
  }

  @Override
  public <T> T required(Supplier<T> work) {
    return required.execute(status -> work.get());
  }
}
