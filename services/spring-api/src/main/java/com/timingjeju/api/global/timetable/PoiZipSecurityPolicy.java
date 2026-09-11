package com.timingjeju.api.global.timetable;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.openxml4j.util.ZipSecureFile;

/** Apache POI의 process-global ZIP limits를 단일 owner가 한 번만 설정한다. */
final class PoiZipSecurityPolicy {
  static final double MIN_INFLATE_RATIO = 0.01d;
  static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
  private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

  private PoiZipSecurityPolicy() {}

  static void ensureInitialized() {
    if (INITIALIZED.compareAndSet(false, true)) {
      ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
      ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
      ZipSecureFile.setMaxTextSize(MAX_ENTRY_BYTES);
    }
  }
}
