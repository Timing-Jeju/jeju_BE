package com.timingjeju.api.global.timetable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class SafeOperatorMappingFileReader {
  static final int MAX_FILE_BYTES = 1024 * 1024;

  private final Path parent;
  private final Path filename;

  SafeOperatorMappingFileReader(Path mappingFile) {
    if (mappingFile == null || !mappingFile.isAbsolute() || mappingFile.getFileName() == null) {
      throw unsafe("UNSAFE_SOURCE_PATH");
    }
    Path normalized = mappingFile.normalize();
    this.parent = realDirectory(normalized.getParent());
    this.filename = normalized.getFileName();
  }

  byte[] read() {
    return AnchoredBoundedFileReader.readAnchoredOrSingleHandle(
        parent, filename, MAX_FILE_BYTES, SafeOperatorMappingFileReader::unsafe);
  }

  private static Path realDirectory(Path directory) {
    if (directory == null || Files.isSymbolicLink(directory)) throw unsafe("UNSAFE_PARENT");
    try {
      Path real = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw unsafe("UNSAFE_PARENT");
      return real;
    } catch (IOException exception) {
      throw unsafe("UNSAFE_PARENT");
    }
  }

  private static IllegalArgumentException unsafe(String reason) {
    return new IllegalArgumentException("unsafe operator mapping file: " + reason);
  }
}
