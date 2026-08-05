package com.timingjeju.api.application.pagination;

public record CursorPageRequest(
    int size, CursorPosition after, CursorContext context, CursorCodec codec) {

  static final int DEFAULT_SIZE = 20;
  static final int MAX_SIZE = 50;

  public CursorPageRequest {
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException("size must be between 1 and 50");
    }
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }
    if (codec == null) {
      throw new IllegalArgumentException("codec must not be null");
    }
  }

  public static CursorPageRequest of(
      Integer size, String encodedCursor, CursorContext context, CursorCodec codec) {
    int normalizedSize = size == null ? DEFAULT_SIZE : size;
    CursorPosition after = encodedCursor == null ? null : codec.decode(encodedCursor, context);
    return new CursorPageRequest(normalizedSize, after, context, codec);
  }
}
