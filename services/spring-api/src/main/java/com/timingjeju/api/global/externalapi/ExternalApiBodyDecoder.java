package com.timingjeju.api.global.externalapi;

@FunctionalInterface
public interface ExternalApiBodyDecoder<T> {
  T decode(byte[] body) throws Exception;
}
