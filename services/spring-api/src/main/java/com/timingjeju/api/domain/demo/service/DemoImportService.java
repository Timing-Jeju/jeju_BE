package com.timingjeju.api.domain.demo.service;

import com.timingjeju.api.application.demo.DemoImportResult;
import com.timingjeju.api.application.demo.DemoStorageView;

public final class DemoImportService {
  private final com.timingjeju.api.application.demo.DemoImportService delegate;

  public DemoImportService(com.timingjeju.api.application.demo.DemoImportService delegate) {
    this.delegate = delegate;
  }

  public DemoImportResult importTourPlaces() {
    return delegate.importTourPlaces();
  }

  public DemoStorageView latestStorage() {
    return delegate.latestStorage();
  }

  public String storageView() {
    return delegate.storageView();
  }
}
