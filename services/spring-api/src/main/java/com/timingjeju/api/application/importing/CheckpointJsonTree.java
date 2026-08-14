package com.timingjeju.api.application.importing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CheckpointJsonTree {

  private static final int POSTGRES_NUMERIC_MAX_INTEGER_DIGITS = 131_072;
  private static final int POSTGRES_NUMERIC_MAX_FRACTION_DIGITS = 16_383;

  private CheckpointJsonTree() {}

  static Map<String, Object> copy(Map<String, Object> checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint는 필수입니다.");
    return copyMap(checkpoint, new IdentityHashMap<>());
  }

  private static Map<String, Object> copyMap(
      Map<?, ?> source, IdentityHashMap<Object, Boolean> activeContainers) {
    enter(source, activeContainers);
    try {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : source.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalidCheckpoint();
        }
        validateString(key);
        copy.put(key, copyValue(entry.getValue(), activeContainers));
      }
      return Collections.unmodifiableMap(copy);
    } finally {
      activeContainers.remove(source);
    }
  }

  private static List<Object> copyList(
      List<?> source, IdentityHashMap<Object, Boolean> activeContainers) {
    enter(source, activeContainers);
    try {
      List<Object> copy = new ArrayList<>(source.size());
      for (Object value : source) {
        copy.add(copyValue(value, activeContainers));
      }
      return Collections.unmodifiableList(copy);
    } finally {
      activeContainers.remove(source);
    }
  }

  private static Object copyValue(Object value, IdentityHashMap<Object, Boolean> activeContainers) {
    if (value == null || value instanceof Boolean) {
      return value;
    }
    if (value instanceof String string) {
      validateString(string);
      return string;
    }
    if (value instanceof Map<?, ?> map) {
      return copyMap(map, activeContainers);
    }
    if (value instanceof List<?> list) {
      return copyList(list, activeContainers);
    }
    if (value instanceof Number number) {
      return normalizeNumber(number);
    }
    throw invalidCheckpoint();
  }

  private static Object normalizeNumber(Number number) {
    if (number instanceof Byte
        || number instanceof Short
        || number instanceof Integer
        || number instanceof Long) {
      return number;
    }
    if (number instanceof BigInteger integer) {
      validateInteger(integer);
      return integer;
    }
    if (number instanceof BigDecimal decimal) {
      validateDecimal(decimal);
      return decimal;
    }
    if (number instanceof Float floating) {
      if (!Float.isFinite(floating)) {
        throw invalidCheckpoint();
      }
      BigDecimal decimal = new BigDecimal(Float.toString(floating));
      validateDecimal(decimal);
      return decimal;
    }
    if (number instanceof Double floating) {
      if (!Double.isFinite(floating)) {
        throw invalidCheckpoint();
      }
      BigDecimal decimal = BigDecimal.valueOf(floating);
      validateDecimal(decimal);
      return decimal;
    }
    throw invalidCheckpoint();
  }

  private static void validateInteger(BigInteger integer) {
    if (integer.abs().toString().length() > POSTGRES_NUMERIC_MAX_INTEGER_DIGITS) {
      throw invalidCheckpoint();
    }
  }

  private static void validateDecimal(BigDecimal decimal) {
    long integerDigits = Math.max((long) decimal.precision() - decimal.scale(), 0L);
    long fractionDigits = Math.max((long) decimal.scale(), 0L);
    if (integerDigits > POSTGRES_NUMERIC_MAX_INTEGER_DIGITS
        || fractionDigits > POSTGRES_NUMERIC_MAX_FRACTION_DIGITS) {
      throw invalidCheckpoint();
    }
  }

  private static void validateString(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '\0') {
        throw invalidCheckpoint();
      }
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
          throw invalidCheckpoint();
        }
      } else if (Character.isLowSurrogate(current)) {
        throw invalidCheckpoint();
      }
    }
  }

  private static void enter(Object container, IdentityHashMap<Object, Boolean> activeContainers) {
    if (activeContainers.put(container, Boolean.TRUE) != null) {
      throw invalidCheckpoint();
    }
  }

  private static ImportCheckpointException invalidCheckpoint() {
    return ImportCheckpointException.of(ImportCheckpointError.INVALID_CHECKPOINT);
  }
}
