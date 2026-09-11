package com.timingjeju.api.global.timetable;

import com.timingjeju.api.application.timetable.TimetableParseException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.Iterator;
import java.util.Locale;

/** StayPolicyCsvReader와 같은 anchored SecureDirectoryStream 경계를 XLSX에 적용한다. */
public final class SafeTimetableFileReader {
  private final Path allowedRoot;
  private final Runnable beforeFileOpen;
  private final DirectoryStreamOpener opener;

  public SafeTimetableFileReader(Path allowedRoot) {
    this(allowedRoot, () -> {}, Files::newDirectoryStream);
  }

  SafeTimetableFileReader(Path allowedRoot, Runnable beforeFileOpen) {
    this(allowedRoot, beforeFileOpen, Files::newDirectoryStream);
  }

  SafeTimetableFileReader(Path allowedRoot, Runnable beforeFileOpen, DirectoryStreamOpener opener) {
    this.allowedRoot = realDirectory(allowedRoot);
    this.beforeFileOpen = beforeFileOpen;
    this.opener = opener;
  }

  public byte[] read(Path requested) {
    Path relative = validatedRelativePath(requested);
    try (DirectoryStream<Path> rootStream = opener.open(allowedRoot)) {
      if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) {
        throw failure("UNSAFE_FILESYSTEM");
      }
      return readAnchored(secureRoot, relative.iterator());
    } catch (TimetableParseException exception) {
      throw exception;
    } catch (IOException exception) {
      throw failure("SOURCE_READ_FAILED");
    }
  }

  private byte[] readAnchored(SecureDirectoryStream<Path> directory, Iterator<Path> parts)
      throws IOException {
    Path component = parts.next();
    if (parts.hasNext()) {
      try (SecureDirectoryStream<Path> child =
          directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)) {
        return readAnchored(child, parts);
      }
    }
    return AnchoredBoundedFileReader.read(
        directory,
        component,
        JejuTimetableXlsxParser.MAX_FILE_BYTES,
        beforeFileOpen,
        SafeTimetableFileReader::failure);
  }

  private Path validatedRelativePath(Path requested) {
    if (requested == null || !requested.isAbsolute()) throw failure("UNSAFE_SOURCE_PATH");
    Path absolute = requested.toAbsolutePath().normalize();
    if (!absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
      throw failure("UNSUPPORTED_FILE_EXTENSION");
    }
    Path relative;
    try {
      relative = allowedRoot.relativize(absolute);
    } catch (IllegalArgumentException exception) {
      throw failure("UNSAFE_SOURCE_PATH");
    }
    if (relative.isAbsolute() || relative.getNameCount() == 0 || startsWithParent(relative)) {
      throw failure("UNSAFE_SOURCE_PATH");
    }
    return relative;
  }

  private static boolean startsWithParent(Path relative) {
    for (Path component : relative) if (component.toString().equals("..")) return true;
    return false;
  }

  private static Path realDirectory(Path root) {
    if (root == null || !root.isAbsolute() || Files.isSymbolicLink(root)) {
      throw failure("UNSAFE_IMPORT_ROOT");
    }
    try {
      Path real = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw failure("UNSAFE_IMPORT_ROOT");
      return real;
    } catch (IOException exception) {
      throw failure("UNSAFE_IMPORT_ROOT");
    }
  }

  private static TimetableParseException failure(String code) {
    return new TimetableParseException(code, "sheet=<file>");
  }

  @FunctionalInterface
  interface DirectoryStreamOpener {
    DirectoryStream<Path> open(Path root) throws IOException;
  }
}
