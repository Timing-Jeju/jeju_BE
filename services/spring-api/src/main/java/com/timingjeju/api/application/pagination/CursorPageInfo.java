package com.timingjeju.api.application.pagination;

public record CursorPageInfo(int size, boolean hasNext, String nextCursor) {}
