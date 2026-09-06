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
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Opens every path component relative to an anchored directory and reads from one no-follow handle.
 */
final class AnchoredBoundedFileReader {
  private static final long OPEN_TIMEOUT_MILLIS = 250;
  private static final long OPEN_CLEANUP_TIMEOUT_MILLIS = 500;
  private static final AtomicLong OPENER_SEQUENCE = new AtomicLong();

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

    BasicFileAttributeView attributeView =
        directory.getFileAttributeView(
            component, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    BasicFileAttributes attributes = attributeView == null ? null : attributeView.readAttributes();
    if (attributes == null || !attributes.isRegularFile()) {
      throw failure.apply("NOT_REGULAR_FILE");
    }

    // The anchored attribute read and anchored open are separate syscalls, not an atomic inode
    // assertion. Run the potentially blocking open in a bounded daemon and, on a regular-to-FIFO
    // swap, pair it with an anchored no-follow writer so both FIFO opens can finish and be closed.
    beforeFileOpen.run();
    try (SeekableByteChannel channel = openBounded(directory, component, failure)) {
      return readBounded(channel, maxBytes, failure);
    }
  }

  private static SeekableByteChannel openBounded(
      SecureDirectoryStream<Path> directory,
      Path component,
      Function<String, ? extends RuntimeException> failure)
      throws IOException {
    Set<OpenOption> readOptions = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable ->
                Thread.ofPlatform()
                    .daemon(true)
                    .name("timing-jeju-timetable-open-" + OPENER_SEQUENCE.incrementAndGet())
                    .unstarted(runnable));
    Future<SeekableByteChannel> opening =
        executor.submit(() -> directory.newByteChannel(component, readOptions));
    try {
      return opening.get(OPEN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      unblockAndCloseTimedOutOpen(directory, component, opening);
      throw failure.apply("SOURCE_OPEN_TIMEOUT");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw failure.apply("SOURCE_READ_FAILED");
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof IOException ioFailure) throw ioFailure;
      if (failed.getCause() instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw failure.apply("SOURCE_READ_FAILED");
    } finally {
      if (!opening.isDone()) opening.cancel(true);
      executor.shutdownNow();
      awaitOpenerTermination(executor, failure);
    }
  }

  private static void unblockAndCloseTimedOutOpen(
      SecureDirectoryStream<Path> directory, Path component, Future<SeekableByteChannel> opening)
      throws IOException {
    Set<OpenOption> writerOptions = Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel ignored = directory.newByteChannel(component, writerOptions)) {
      // Opening the anchored writer releases a reader blocked on a swapped FIFO.
    }
    try (SeekableByteChannel timedOutReader =
        opening.get(OPEN_CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
      // The one opened read handle is closed without reading after the timeout decision.
    } catch (TimeoutException timeout) {
      throw new IOException("anchored source opener did not terminate", timeout);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while cleaning anchored source opener", interrupted);
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof IOException ioFailure) throw ioFailure;
      throw new IOException("anchored source opener failed", failed.getCause());
    }
  }

  private static void awaitOpenerTermination(
      ExecutorService executor, Function<String, ? extends RuntimeException> failure) {
    try {
      if (!executor.awaitTermination(OPEN_CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        throw failure.apply("SOURCE_OPEN_WORKER_STUCK");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw failure.apply("SOURCE_READ_FAILED");
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
