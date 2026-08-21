package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class KmaWeatherSourceResponse {
  private static final byte[] MAGIC = {'K', 'M', 'A', '1'};
  private final List<KmaWeatherResponsePart> parts;
  private final boolean transportFailed;

  public KmaWeatherSourceResponse(byte[] payload, SnapshotPayloadFormat format) {
    this(List.of(new KmaWeatherResponsePart("single", null, payload, format)));
  }

  public KmaWeatherSourceResponse(List<KmaWeatherResponsePart> parts) {
    this(parts, false);
  }

  private KmaWeatherSourceResponse(List<KmaWeatherResponsePart> parts, boolean transportFailed) {
    Objects.requireNonNull(parts, "parts는 필수입니다.");
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("parts는 비어 있을 수 없습니다.");
    }
    this.parts = List.copyOf(parts);
    this.transportFailed = transportFailed;
  }

  public static KmaWeatherSourceResponse transportFailure(List<KmaWeatherResponsePart> parts) {
    return new KmaWeatherSourceResponse(parts, true);
  }

  public List<KmaWeatherResponsePart> parts() {
    return parts;
  }

  public boolean transportFailed() {
    return transportFailed;
  }

  public byte[] payload() {
    if (parts.size() == 1 && "single".equals(parts.getFirst().providerOperation())) {
      return parts.getFirst().payload();
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.write(MAGIC);
        output.writeInt(parts.size());
        for (KmaWeatherResponsePart part : parts) {
          byte[] operation = part.providerOperation().getBytes(StandardCharsets.UTF_8);
          byte[] payload = part.payload();
          output.writeInt(operation.length);
          output.write(operation);
          output.writeInt(part.pageNumber() == null ? 0 : part.pageNumber());
          output.writeByte(part.format().ordinal());
          output.writeInt(payload.length);
          output.write(payload);
        }
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("메모리 내 응답 프레이밍에 실패했습니다.", impossible);
    }
  }

  public SnapshotPayloadFormat format() {
    return parts.size() == 1 ? parts.getFirst().format() : SnapshotPayloadFormat.BINARY;
  }

  public static List<KmaWeatherResponsePart> decode(byte[] payload) {
    Objects.requireNonNull(payload, "payload는 필수입니다.");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      byte[] magic = input.readNBytes(MAGIC.length);
      if (!java.util.Arrays.equals(magic, MAGIC)) {
        throw new IllegalArgumentException("KMA 다중 응답 프레임이 아닙니다.");
      }
      int count = input.readInt();
      if (count < 1 || count > 100) {
        throw new IllegalArgumentException("KMA 응답 part 개수가 올바르지 않습니다.");
      }
      List<KmaWeatherResponsePart> decoded = new ArrayList<>(count);
      for (int index = 0; index < count; index++) {
        int operationLength = input.readInt();
        if (operationLength < 1 || operationLength > 100) {
          throw new IllegalArgumentException("KMA operation 길이가 올바르지 않습니다.");
        }
        String operation = new String(input.readNBytes(operationLength), StandardCharsets.UTF_8);
        int page = input.readInt();
        int format = input.readUnsignedByte();
        int length = input.readInt();
        if (length < 0 || length > 20_000_000 || format >= SnapshotPayloadFormat.values().length) {
          throw new IllegalArgumentException("KMA 응답 part 메타데이터가 올바르지 않습니다.");
        }
        byte[] partPayload = input.readNBytes(length);
        if (partPayload.length != length) {
          throw new IllegalArgumentException("KMA 응답 part가 잘렸습니다.");
        }
        decoded.add(
            new KmaWeatherResponsePart(
                operation,
                page == 0 ? null : page,
                partPayload,
                SnapshotPayloadFormat.values()[format]));
      }
      if (input.read() != -1) {
        throw new IllegalArgumentException("KMA 응답 프레임 뒤에 데이터가 남았습니다.");
      }
      return List.copyOf(decoded);
    } catch (IOException failure) {
      throw new IllegalArgumentException("KMA 다중 응답 프레임이 올바르지 않습니다.", failure);
    }
  }
}
