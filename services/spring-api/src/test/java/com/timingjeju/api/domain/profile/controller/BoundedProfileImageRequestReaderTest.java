package com.timingjeju.api.domain.profile.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.profile.ProfileImageException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class BoundedProfileImageRequestReaderTest {

  private static final int MAX = 1024 * 1024;

  @Test
  void exact_MAX는_byte_for_byte_그대로_한번만_읽어_반환한다() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    TrackingServletInputStream input = new TrackingServletInputStream(MAX);
    when(request.getContentLengthLong()).thenReturn((long) MAX);
    when(request.getInputStream()).thenReturn(input);

    byte[] result = new BoundedProfileImageRequestReader().read(request);

    byte[] expected = new byte[MAX];
    java.util.Arrays.fill(expected, (byte) ' ');
    assertThat(result).containsExactly(expected);
    assertThat(input.bytesRead).isEqualTo(MAX);
  }

  @Test
  void absent_content_length의_MAX_plus_1은_MAX_plus_1까지만_읽고_transport_400이다() throws Exception {
    assertStreamBounded(-1, null);
  }

  @Test
  void underreported_content_length의_MAX_plus_1도_MAX_plus_1까지만_읽고_transport_400이다()
      throws Exception {
    assertStreamBounded(1, null);
  }

  @Test
  void chunked_MAX_plus_1도_MAX_plus_1까지만_읽고_transport_400이다() throws Exception {
    assertStreamBounded(-1, "chunked");
  }

  @Test
  void declared_MAX_plus_1은_input_stream을_materialize하지_않고_거부한다() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong()).thenReturn((long) MAX + 1);

    assertInvalid(() -> new BoundedProfileImageRequestReader().read(request));

    verify(request, never()).getInputStream();
  }

  private static void assertStreamBounded(long contentLength, String transferEncoding)
      throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    TrackingServletInputStream input = new TrackingServletInputStream(MAX + 2);
    when(request.getContentLengthLong()).thenReturn(contentLength);
    when(request.getHeader("Transfer-Encoding")).thenReturn(transferEncoding);
    when(request.getInputStream()).thenReturn(input);

    assertInvalid(() -> new BoundedProfileImageRequestReader().read(request));

    assertThat(input.bytesRead).isEqualTo(MAX + 1);
  }

  private static void assertInvalid(ThrowingRunnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo("INVALID_PROFILE_IMAGE_REQUEST");
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class TrackingServletInputStream extends ServletInputStream {
    private int remaining;
    private int bytesRead;

    private TrackingServletInputStream(int size) {
      remaining = size;
    }

    @Override
    public int read() {
      if (remaining == 0) {
        return -1;
      }
      remaining--;
      bytesRead++;
      return ' ';
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      if (remaining == 0) {
        return -1;
      }
      int count = Math.min(remaining, length);
      java.util.Arrays.fill(target, offset, offset + count, (byte) ' ');
      remaining -= count;
      bytesRead += count;
      return count;
    }

    @Override
    public boolean isFinished() {
      return remaining == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {}
  }
}
