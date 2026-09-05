package com.timingjeju.api.global.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.timingjeju.api.application.timetable.TimetableParseException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

  private static boolean supportsSecureDirectoryStream(Path directory) throws Exception {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      return stream instanceof java.nio.file.SecureDirectoryStream<?>;
    }
  }
}
