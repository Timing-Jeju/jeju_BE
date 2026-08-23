package com.timingjeju.api.application.text;

@FunctionalInterface
public interface PublicPlainTextNormalizer {

  int MAX_CODE_POINTS = 1000;

  String normalize(String value);
}
