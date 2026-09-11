package com.timingjeju.api.support.postgresql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class ImageResourcePool<R extends AutoCloseable> implements AutoCloseable {
  private final Function<String, R> resourceFactory;
  private final Map<String, R> resources = new LinkedHashMap<>();
  private boolean closed;

  ImageResourcePool(Function<String, R> resourceFactory) {
    this.resourceFactory = resourceFactory;
  }

  synchronized R get(String image) {
    if (closed) throw new IllegalStateException("이미 종료한 image resource pool입니다.");
    return resources.computeIfAbsent(image, resourceFactory);
  }

  synchronized int size() {
    return resources.size();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    List<R> reverse = new ArrayList<>(resources.values());
    RuntimeException failure = null;
    for (int index = reverse.size() - 1; index >= 0; index--) {
      try {
        reverse.get(index).close();
      } catch (Exception exception) {
        if (failure == null) failure = new IllegalStateException("image resource 정리 실패");
        failure.addSuppressed(exception);
      }
    }
    resources.clear();
    if (failure != null) throw failure;
  }
}
