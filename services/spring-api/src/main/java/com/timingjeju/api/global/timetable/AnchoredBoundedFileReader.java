package com.timingjeju.api.global.timetable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Function;

/**
 * Opens every path component relative to an anchored directory and reads from one no-follow handle.
 */
final class AnchoredBoundedFileReader {
  private AnchoredBoundedFileReader() {}

  static byte[] read(
      Path root,
      Path relative,
      long maxBytes,
      Function<String, ? extends RuntimeException> failure) {
    try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(root)) {
      if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) {
        throw failure.apply("UNSAFE_FILESYSTEM");
      }
      return readAnchored(secureRoot, relative.iterator(), maxBytes, failure);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (IOException exception) {
      throw failure.apply("SOURCE_READ_FAILED");
    }
  }

  static byte[] readAnchoredOrSingleHandle(
      Path root,
      Path relative,
      long maxBytes,
      Function<String, ? extends RuntimeException> failure) {
    try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(root)) {
      if (rootStream instanceof SecureDirectoryStream<Path> secureRoot) {
        return readAnchored(secureRoot, relative.iterator(), maxBytes, failure);
      }
    } catch (RuntimeException exception) {
      throw exception;
    } catch (IOException exception) {
      throw failure.apply("SOURCE_READ_FAILED");
    }

    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(root.resolve(relative), options)) {
      return readBounded(channel, maxBytes, failure);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (IOException exception) {
      throw failure.apply("SOURCE_READ_FAILED");
    }
  }

  static byte[] read(
      SecureDirectoryStream<Path> root,
      Path relative,
      long maxBytes,
      Runnable beforeFileOpen,
      Function<String, ? extends RuntimeException> failure)
      throws IOException {
    return readAnchored(root, relative.iterator(), maxBytes, beforeFileOpen, failure);
  }

  private static byte[] readAnchored(
      SecureDirectoryStream<Path> directory,
      Iterator<Path> parts,
      long maxBytes,
      Function<String, ? extends RuntimeException> failure)
      throws IOException {
    return readAnchored(directory, parts, maxBytes, () -> {}, failure);
  }

  private static byte[] readAnchored(
      SecureDirectoryStream<Path> directory,
      Iterator<Path> parts,
      long maxBytes,
      Runnable beforeFileOpen,
      Function<String, ? extends RuntimeException> failure)
      throws IOException {
    Path component = parts.next();
    if (parts.hasNext()) {
      try (SecureDirectoryStream<Path> child =
          directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)) {
        return readAnchored(child, parts, maxBytes, beforeFileOpen, failure);
      }
    }

    beforeFileOpen.run();
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = directory.newByteChannel(component, options)) {
      return readBounded(channel, maxBytes, failure);
    }
  }

  private static byte[] readBounded(
      SeekableByteChannel channel,
      long maxBytes,
      Function<String, ? extends RuntimeException> failure)
      throws IOException {
    long size = channel.size();
    if (size < 0 || size > maxBytes) throw failure.apply("FILE_TOO_LARGE");

    ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
    ByteBuffer buffer = ByteBuffer.allocate(8192);
    int total = 0;
    int read;
    while ((read = channel.read(buffer)) != -1) {
      if (read == 0) continue;
      buffer.flip();
      total = Math.addExact(total, read);
      if (total > maxBytes) throw failure.apply("FILE_TOO_LARGE");
      output.write(buffer.array(), buffer.position(), buffer.remaining());
      buffer.clear();
    }
    return output.toByteArray();
  }
}
