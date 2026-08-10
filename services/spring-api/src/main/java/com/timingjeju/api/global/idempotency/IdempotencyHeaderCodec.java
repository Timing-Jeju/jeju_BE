package com.timingjeju.api.global.idempotency;

import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class IdempotencyHeaderCodec {

  private static final int VERSION = 1;
  private static final int MAX_HEADERS = 128;
  private static final int MAX_FIELD_BYTES = 16_384;

  byte[] encode(List<IdempotencyHeader> headers) {
    if (headers.size() > MAX_HEADERS) {
      throw new IllegalArgumentException("저장할 response header가 너무 많습니다.");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeInt(VERSION);
      output.writeInt(headers.size());
      for (IdempotencyHeader header : headers) {
        write(output, header.name());
        write(output, header.value());
      }
      output.flush();
      byte[] encoded = bytes.toByteArray();
      if (encoded.length > 65_536) {
        throw new IllegalArgumentException("저장할 response header는 64 KiB 이하여야 합니다.");
      }
      return encoded;
    } catch (IOException exception) {
      throw new IllegalStateException("response header를 직렬화할 수 없습니다.", exception);
    }
  }

  List<IdempotencyHeader> decode(byte[] encoded) {
    try {
      DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
      if (input.readInt() != VERSION) {
        throw new IllegalStateException("지원하지 않는 idempotency header 형식입니다.");
      }
      int count = input.readInt();
      if (count < 0 || count > MAX_HEADERS) {
        throw new IllegalStateException("저장된 response header 개수가 올바르지 않습니다.");
      }
      List<IdempotencyHeader> headers = new ArrayList<>(count);
      for (int index = 0; index < count; index++) {
        headers.add(new IdempotencyHeader(read(input), read(input)));
      }
      if (input.available() != 0) {
        throw new IllegalStateException("저장된 response header에 후행 데이터가 있습니다.");
      }
      return List.copyOf(headers);
    } catch (IOException exception) {
      throw new IllegalStateException("저장된 response header를 읽을 수 없습니다.", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_FIELD_BYTES) {
      throw new IllegalArgumentException("response header field가 너무 큽니다.");
    }
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String read(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > MAX_FIELD_BYTES || length > input.available()) {
      throw new IllegalStateException("저장된 response header field 길이가 올바르지 않습니다.");
    }
    return new String(input.readNBytes(length), StandardCharsets.UTF_8);
  }
}
