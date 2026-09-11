package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("unit")
class ImageResourcePoolTest {

  @Test
  void 같은_이미지는_case_수와_무관하게_한번만_시작한다() {
    AtomicInteger starts = new AtomicInteger();
    try (var pool =
        new ImageResourcePool<>(image -> new Resource(image, starts, new ArrayList<>()))) {
      Resource first = pool.get("postgis/postgis:16-3.4");
      Resource second = pool.get("postgis/postgis:16-3.4");
      Resource third = pool.get("postgis/postgis:17-3.5");

      assertThat(second).isSameAs(first);
      assertThat(third).isNotSameAs(first);
      assertThat(starts).hasValue(2);
      assertThat(pool.size()).isEqualTo(2);
    }
  }

  @Test
  void pool은_시작한_리소스를_역순으로_모두_정리한다() {
    List<String> closed = new ArrayList<>();
    var pool = new ImageResourcePool<>(image -> new Resource(image, new AtomicInteger(), closed));
    pool.get("pg16");
    pool.get("pg17");

    pool.close();

    assertThat(closed).containsExactly("pg17", "pg16");
  }

  @Test
  void 한_리소스_정리가_실패해도_나머지_리소스를_계속_정리한다() {
    List<String> closed = new ArrayList<>();
    var pool =
        new ImageResourcePool<>(
            image ->
                (AutoCloseable)
                    () -> {
                      closed.add(image);
                      if (image.equals("pg17")) throw new IllegalStateException("cleanup failed");
                    });
    pool.get("pg16");
    pool.get("pg17");

    assertThatThrownBy(pool::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("image resource 정리 실패")
        .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
    assertThat(closed).containsExactly("pg17", "pg16");
  }

  @Test
  void readiness_진단은_image_container_session_elapsed와_log를_포함한다() {
    String diagnostic =
        PostgreSqlImageFixturePool.startupDiagnostic(
            "postgis/postgis:16-3.4",
            "container-247",
            "session-247",
            Duration.ofSeconds(9),
            "ready timeout");

    assertThat(diagnostic)
        .contains(
            "image=postgis/postgis:16-3.4",
            "container=container-247",
            "session=session-247",
            "elapsed=PT9S",
            "logs=ready timeout");
  }

  @Test
  void container_시작_실패는_진단을_남기고_즉시_정리하며_pool에_등록하지_않는다() {
    PostgreSQLContainer container = mock(PostgreSQLContainer.class);
    when(container.withLabel(anyString(), anyString())).thenReturn(container);
    when(container.getContainerId()).thenReturn("container-247");
    when(container.getLogs()).thenReturn("ready timeout");
    IllegalStateException startupFailure = new IllegalStateException("not ready");
    doThrow(startupFailure).when(container).start();

    try (var pool = new PostgreSqlImageFixturePool("session-247", image -> container)) {
      assertThatThrownBy(() -> pool.open("postgis/postgis:16-3.4"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(
              "image=postgis/postgis:16-3.4",
              "container=container-247",
              "session=session-247",
              "elapsed=",
              "logs=ready timeout")
          .hasCause(startupFailure);

      assertThat(pool.startedContainerCount()).isZero();
      verify(container).stop();
    }
  }

  private static final class Resource implements AutoCloseable {
    private final String image;
    private final List<String> closed;

    private Resource(String image, AtomicInteger starts, List<String> closed) {
      this.image = image;
      this.closed = closed;
      starts.incrementAndGet();
    }

    @Override
    public void close() {
      closed.add(image);
    }
  }
}
