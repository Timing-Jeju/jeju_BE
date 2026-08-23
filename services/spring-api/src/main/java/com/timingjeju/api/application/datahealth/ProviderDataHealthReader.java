package com.timingjeju.api.application.datahealth;

import java.util.List;

@FunctionalInterface
public interface ProviderDataHealthReader {
  List<ProviderDataHealthHistory> read(List<ProviderDataHealthKey> keys);
}
