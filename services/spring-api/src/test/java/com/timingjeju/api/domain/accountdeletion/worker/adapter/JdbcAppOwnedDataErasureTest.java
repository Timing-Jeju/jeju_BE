package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionLease;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@Tag("unit")
class JdbcAppOwnedDataErasureTest {
  private static final UUID USER_ID = UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7f40");
  private static final DeletionLease LEASE =
      new DeletionLease("01K4V106000000000000000001", "worker", 7, 2);

  @Test
  void 짧은_fenced_transaction에서_앱데이터를_삭제하고_보존데이터와_profile을_비식별화한다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("for update"), anyMap(), eq(UUID.class)))
        .thenReturn(USER_ID);
    var adapter = new JdbcAppOwnedDataErasure(jdbc, directTransaction());

    adapter.deleteAndAnonymize(LEASE, AuthSubject.of(USER_ID.toString()));

    InOrder order = inOrder(jdbc);
    order
        .verify(jdbc)
        .queryForObject(
            org.mockito.ArgumentMatchers.contains("for update"), anyMap(), eq(UUID.class));
    order
        .verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("delete from public.trip_plans"), anyMap());
    order
        .verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("delete from public.app_sessions"), anyMap());
    order
        .verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("update public.user_consents"), anyMap());
    order
        .verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("update public.user_profiles"), anyMap());
  }

  @Test
  void owner와_fence가_맞지_않으면_어떤_개인정보도_변경하지_않는다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("for update"), anyMap(), eq(UUID.class)))
        .thenReturn(null);
    var adapter = new JdbcAppOwnedDataErasure(jdbc, directTransaction());

    assertThatThrownBy(() -> adapter.deleteAndAnonymize(LEASE, AuthSubject.of(USER_ID.toString())))
        .isInstanceOf(DeletionOperationException.class)
        .hasMessage("ACCOUNT_DELETION_LEASE_LOST");
    verify(jdbc, never()).update(org.mockito.ArgumentMatchers.anyString(), anyMap());
  }

  private static TransactionOperations directTransaction() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        return action.doInTransaction(mock(TransactionStatus.class));
      }
    };
  }
}
