package com.timingjeju.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NaverUserInfoAdmissionServiceTest {

  @Test
  void 애플리케이션_rate_limit은_429로_거부하고_window_후_회복한다() {
    AtomicLong ticker = new AtomicLong();
    NaverUserInfoAdmissionService service =
        NaverUserInfoAdmissionService.forTest(2, Duration.ofSeconds(1), 2, ticker::get);
    AtomicInteger outboundCalls = new AtomicInteger();

    assertThat(service.execute(() -> countAndReturn(outboundCalls, "first"))).isEqualTo("first");
    assertThat(service.execute(() -> countAndReturn(outboundCalls, "second"))).isEqualTo("second");
    assertThatThrownBy(() -> service.execute(() -> countAndReturn(outboundCalls, "rejected")))
        .isInstanceOf(NaverUserInfoException.class)
        .extracting(exception -> ((NaverUserInfoException) exception).code().name())
        .isEqualTo("APPLICATION_RATE_LIMITED");
    assertThat(outboundCalls).hasValue(2);

    ticker.addAndGet(Duration.ofSeconds(1).toNanos());

    assertThat(service.execute(() -> countAndReturn(outboundCalls, "recovered")))
        .isEqualTo("recovered");
    assertThat(outboundCalls).hasValue(3);
  }

  @Test
  void bulkhead는_outbound_동시호출을_상한으로_제한하고_완료_후_회복한다() throws Exception {
    NaverUserInfoAdmissionService service =
        NaverUserInfoAdmissionService.forTest(100, Duration.ofSeconds(1), 2, System::nanoTime);
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> accepted = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        accepted.add(
            executor.submit(
                () ->
                    service.execute(
                        () -> {
                          int current = active.incrementAndGet();
                          maxActive.accumulateAndGet(current, Math::max);
                          entered.countDown();
                          try {
                            release.await(2, TimeUnit.SECONDS);
                            return "ok";
                          } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                          } finally {
                            active.decrementAndGet();
                          }
                        })));
      }

      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> service.execute(() -> "third"))
          .isInstanceOf(NaverUserInfoException.class)
          .extracting(exception -> ((NaverUserInfoException) exception).code().name())
          .isEqualTo("APPLICATION_OVERLOADED");

      release.countDown();
      for (Future<String> future : accepted) {
        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
      }
      assertThat(maxActive).hasValue(2);
      assertThat(service.execute(() -> "recovered")).isEqualTo("recovered");
    }
  }

  private static String countAndReturn(AtomicInteger counter, String value) {
    counter.incrementAndGet();
    return value;
  }
}
