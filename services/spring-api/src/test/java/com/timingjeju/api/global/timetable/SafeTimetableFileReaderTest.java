package com.timingjeju.api.global.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.timetable.TimetableParseException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class SafeTimetableFileReaderTest {
  @TempDir Path temporary;

  @Test
  void allowlist_root의_regular_file만_nofollow로_읽는다() throws Exception {
    Path allowed = Files.createDirectory(temporary.resolve("allowed"));
    assumeTrue(supportsSecureDirectoryStream(allowed));
    Path file = allowed.resolve("101.xlsx");
    Files.write(file, new byte[] {1, 2, 3});
    assertThat(new SafeTimetableFileReader(allowed).read(file)).containsExactly(1, 2, 3);
  }

  @Test
  void path_escape_directory와_symlink는_읽기전에_거부한다() throws Exception {
    Path allowed = Files.createDirectory(temporary.resolve("allowed"));
    Path outside = temporary.resolve("outside.xlsx");
    Files.write(outside, new byte[] {1});
    var reader = new SafeTimetableFileReader(allowed);
    assertThatThrownBy(() -> reader.read(allowed.resolve("../outside.xlsx")))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageContaining("UNSAFE_SOURCE_PATH");
    Path directory = Files.createDirectory(allowed.resolve("directory.xlsx"));
    assertThatThrownBy(() -> reader.read(directory))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageMatching(".*(UNSAFE_SOURCE_PATH|UNSAFE_FILESYSTEM).*");
    Path link = allowed.resolve("link.xlsx");
    Files.createSymbolicLink(link, outside);
    assertThatThrownBy(() -> reader.read(link))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageMatching(".*(UNSAFE_SOURCE_PATH|UNSAFE_FILESYSTEM).*");
  }

  @Test
  void relative_wrong_extension_root와_parent_symlink를_fail_closed한다() throws Exception {
    Path allowed = Files.createDirectory(temporary.resolve("allowed"));
    Path outsideDir = Files.createDirectory(temporary.resolve("outside"));
    Files.write(outsideDir.resolve("201.xlsx"), new byte[] {1});
    assertThatThrownBy(() -> new SafeTimetableFileReader(allowed).read(Path.of("201.xlsx")))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageContaining("UNSAFE_SOURCE_PATH");
    Path csv = allowed.resolve("201.csv");
    Files.write(csv, new byte[] {1});
    assertThatThrownBy(() -> new SafeTimetableFileReader(allowed).read(csv))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageContaining("UNSUPPORTED_FILE_EXTENSION");
    Path parentLink = allowed.resolve("linked-parent");
    Files.createSymbolicLink(parentLink, outsideDir);
    assertThatThrownBy(
            () -> new SafeTimetableFileReader(allowed).read(parentLink.resolve("201.xlsx")))
        .isInstanceOf(TimetableParseException.class);
    Path rootLink = temporary.resolve("root-link");
    Files.createSymbolicLink(rootLink, allowed);
    assertThatThrownBy(() -> new SafeTimetableFileReader(rootLink))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageContaining("UNSAFE_IMPORT_ROOT");
  }

  @Test
  void attribute검사직전_swap과_SecureDirectoryStream미지원은_거부한다() throws Exception {
    Path allowed = Files.createDirectory(temporary.resolve("allowed"));
    Path file = allowed.resolve("101.xlsx");
    Path outside = temporary.resolve("outside.xlsx");
    Files.write(file, new byte[] {1});
    Files.write(outside, new byte[] {2});
    if (supportsSecureDirectoryStream(allowed)) {
      var swapping =
          new SafeTimetableFileReader(
              allowed,
              () -> {
                try {
                  Files.delete(file);
                  Files.createSymbolicLink(file, outside);
                } catch (java.io.IOException failure) {
                  throw new IllegalStateException(failure);
                }
              });
      assertThatThrownBy(() -> swapping.read(file)).isInstanceOf(TimetableParseException.class);
    }

    DirectoryStream<Path> insecure =
        new DirectoryStream<>() {
          public Iterator<Path> iterator() {
            return java.util.List.<Path>of().iterator();
          }

          public void close() {}
        };
    var unsupported = new SafeTimetableFileReader(allowed, () -> {}, ignored -> insecure);
    assertThatThrownBy(() -> unsupported.read(allowed.resolve("another.xlsx")))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageContaining("UNSAFE_FILESYSTEM");
  }

  @Test
  @SuppressWarnings("unchecked")
  void anchored_handle의_파일속성이_regular일때만_nofollow로_연_단일_handle에서_읽는다() throws Exception {
    Path allowed = Files.createDirectory(temporary.resolve("allowed"));
    SecureDirectoryStream<Path> root = mock(SecureDirectoryStream.class);
    SeekableByteChannel channel = mock(SeekableByteChannel.class);
    BasicFileAttributeView attributesView = mock(BasicFileAttributeView.class);
    BasicFileAttributes attributes = mock(BasicFileAttributes.class);
    byte[] content = {4, 5, 6};
    AtomicBoolean firstRead = new AtomicBoolean(true);
    when(root.getFileAttributeView(
            Path.of("101.xlsx"), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS))
        .thenReturn(attributesView);
    when(attributesView.readAttributes()).thenReturn(attributes);
    when(attributes.isRegularFile()).thenReturn(true);
    when(root.newByteChannel(eq(Path.of("101.xlsx")), any(Set.class))).thenReturn(channel);
    when(channel.size()).thenReturn((long) content.length);
    when(channel.read(any(ByteBuffer.class)))
        .thenAnswer(
            invocation -> {
              if (!firstRead.getAndSet(false)) return -1;
              invocation.<ByteBuffer>getArgument(0).put(content);
              return content.length;
            });

    var reader = new SafeTimetableFileReader(allowed, () -> {}, ignored -> root);

    assertThat(reader.read(allowed.resolve("101.xlsx"))).containsExactly(content);
    verify(root)
        .newByteChannel(
            Path.of("101.xlsx"),
            Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    verify(root)
        .getFileAttributeView(
            Path.of("101.xlsx"), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
  }

  @Test
  @SuppressWarnings("unchecked")
  void anchored_handle에서_regular가_아닌_파일은_open전에_거부한다() throws Exception {
    Path allowed = Files.createDirectory(temporary.resolve("allowed"));
    SecureDirectoryStream<Path> root = mock(SecureDirectoryStream.class);
    BasicFileAttributeView attributesView = mock(BasicFileAttributeView.class);
    BasicFileAttributes attributes = mock(BasicFileAttributes.class);
    when(root.getFileAttributeView(
            Path.of("101.xlsx"), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS))
        .thenReturn(attributesView);
    when(attributesView.readAttributes()).thenReturn(attributes);
    when(attributes.isRegularFile()).thenReturn(false);

    var reader = new SafeTimetableFileReader(allowed, () -> {}, ignored -> root);

    assertThatThrownBy(() -> reader.read(allowed.resolve("101.xlsx")))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageContaining("NOT_REGULAR_FILE");
    verify(root, never()).newByteChannel(any(Path.class), any(Set.class));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void Linux_FIFO는_block하지_않고_bounded_fail한다() throws Exception {
    Path allowed = Files.createDirectory(temporary.resolve("allowed"));
    Path fifo = allowed.resolve("fifo.xlsx");
    assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();

    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () ->
            assertThatThrownBy(() -> new SafeTimetableFileReader(allowed).read(fifo))
                .isInstanceOf(TimetableParseException.class)
                .hasMessageContaining("NOT_REGULAR_FILE"));
  }

  private static boolean supportsSecureDirectoryStream(Path directory) throws Exception {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      return stream instanceof java.nio.file.SecureDirectoryStream<?>;
    }
  }
}
