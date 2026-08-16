package com.timingjeju.api.global.staypolicy;

import com.timingjeju.api.application.staypolicy.StayPolicyCandidate;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class StayPolicyCsvReader {

  private static final String HEADER = "scope,category,placeId,minutes";
  private static final long MAX_FILE_BYTES = 1_048_576;
  private static final int MAX_ROWS = 10_000;

  private final Path importRoot;
  private final Runnable beforeFileOpen;

  public StayPolicyCsvReader(Path importRoot) {
    this(importRoot, () -> {});
  }

  StayPolicyCsvReader(Path importRoot, Runnable beforeFileOpen) {
    this.importRoot = realDirectory(importRoot);
    this.beforeFileOpen = beforeFileOpen;
  }

  public List<StayPolicyCandidate> read(Path requestedFile) {
    Path relative = validatedRelativePath(requestedFile);
    String content = readFromAnchoredRoot(relative);
    return parseContent(content);
  }

  static List<StayPolicyCandidate> parseContent(String content) {
    rejectControlCharacters(content);
    String[] lines = content.split("\\R", -1);
    if (lines.length == 0 || !HEADER.equals(lines[0])) {
      throw new StayPolicyFileException("Stay policy CSV must use the exact header");
    }
    List<StayPolicyCandidate> policies = new ArrayList<>();
    for (int lineNumber = 2; lineNumber <= lines.length; lineNumber++) {
      String line = lines[lineNumber - 1];
      if (line.isEmpty() && lineNumber == lines.length) {
        continue;
      }
      if (line.isBlank()) {
        throw malformed(lineNumber, "blank rows are not allowed");
      }
      if (policies.size() >= MAX_ROWS) {
        throw new StayPolicyFileException("Stay policy CSV exceeds 10000 policy rows");
      }
      policies.add(parseLine(line, lineNumber));
    }
    return List.copyOf(policies);
  }

  private Path validatedRelativePath(Path requestedFile) {
    if (requestedFile == null || !requestedFile.isAbsolute()) {
      throw new StayPolicyFileException("Stay policy CSV path must be absolute");
    }
    Path absolute = requestedFile.toAbsolutePath().normalize();
    if (!absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
      throw new StayPolicyFileException("Stay policy import supports .csv files only");
    }
    Path relative = importRoot.relativize(absolute);
    if (relative.isAbsolute()
        || relative.getNameCount() == 0
        || containsParentTraversal(relative)) {
      throw new StayPolicyFileException(
          "Stay policy CSV must stay inside the configured import root");
    }
    return relative;
  }

  private static boolean containsParentTraversal(Path relative) {
    for (Path component : relative) {
      if ("..".equals(component.toString())) {
        return true;
      }
    }
    return false;
  }

  private String readFromAnchoredRoot(Path relative) {
    try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(importRoot)) {
      if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) {
        throw new StayPolicyFileException(
            "Stay policy import filesystem does not support secure path access");
      }
      return readFromDirectory(secureRoot, relative.iterator());
    } catch (StayPolicyFileException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new StayPolicyFileException(
          "Stay policy CSV path contains a symbolic link or could not be read", exception);
    }
  }

  private String readFromDirectory(SecureDirectoryStream<Path> directory, Iterator<Path> parts)
      throws IOException {
    Path component = parts.next();
    if (parts.hasNext()) {
      try (SecureDirectoryStream<Path> child =
          directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)) {
        return readFromDirectory(child, parts);
      }
    }
    beforeFileOpen.run();
    BasicFileAttributeView view =
        directory.getFileAttributeView(
            component, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    BasicFileAttributes attributes = view.readAttributes();
    if (!attributes.isRegularFile()) {
      throw new StayPolicyFileException(
          "Stay policy CSV path contains a symbolic link or is not a regular file");
    }
    Set<OpenOption> options = new HashSet<>();
    options.add(StandardOpenOption.READ);
    options.add(LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = directory.newByteChannel(component, options)) {
      return readBounded(channel);
    }
  }

  private static String readBounded(SeekableByteChannel channel) throws IOException {
    if (channel.size() > MAX_FILE_BYTES) {
      throw new StayPolicyFileException("Stay policy CSV exceeds 1 MiB");
    }
    ByteBuffer buffer = ByteBuffer.allocate((int) MAX_FILE_BYTES + 1);
    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
      // Continue until EOF or until one byte beyond the limit has been read.
    }
    if (buffer.position() > MAX_FILE_BYTES) {
      throw new StayPolicyFileException("Stay policy CSV exceeds 1 MiB");
    }
    buffer.flip();
    return StandardCharsets.UTF_8.decode(buffer).toString();
  }

  private static Path realDirectory(Path root) {
    if (root == null || !root.isAbsolute()) {
      throw new StayPolicyFileException("Stay policy import root must be absolute");
    }
    if (Files.isSymbolicLink(root)) {
      throw new StayPolicyFileException("Stay policy import root must not be a symbolic link");
    }
    try {
      Path real = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
        throw new StayPolicyFileException("Stay policy import root must be a directory");
      }
      return real;
    } catch (IOException exception) {
      throw new StayPolicyFileException("Stay policy import root is unavailable", exception);
    }
  }

  private static StayPolicyCandidate parseLine(String line, int lineNumber) {
    if (line.indexOf('"') >= 0) {
      throw malformed(lineNumber, "quoted or multiline fields are not supported");
    }
    String[] fields = line.split(",", -1);
    if (fields.length != 4) {
      throw malformed(lineNumber, "exactly four fields are required");
    }
    for (String field : fields) {
      int first = firstNonWhitespace(field);
      if (first < field.length() && "=+-@".indexOf(field.charAt(first)) >= 0) {
        throw malformed(lineNumber, "formula or macro fields are not allowed");
      }
    }
    int minutes;
    try {
      minutes = Integer.parseInt(fields[3]);
    } catch (NumberFormatException exception) {
      throw malformed(lineNumber, "minutes must be an integer");
    }
    return switch (fields[0]) {
      case "category_default" -> {
        if (fields[1].isEmpty() || !fields[2].isEmpty()) {
          throw malformed(lineNumber, "category_default requires only category");
        }
        yield StayPolicyCandidate.categoryDefault(fields[1], minutes);
      }
      case "place_override" -> {
        if (!fields[1].isEmpty() || fields[2].isEmpty()) {
          throw malformed(lineNumber, "place_override requires only placeId");
        }
        try {
          yield StayPolicyCandidate.placeOverride(UUID.fromString(fields[2]), minutes);
        } catch (IllegalArgumentException exception) {
          throw malformed(lineNumber, "placeId must be a UUID");
        }
      }
      default -> throw malformed(lineNumber, "unknown scope");
    };
  }

  private static int firstNonWhitespace(String field) {
    int index = 0;
    while (index < field.length()) {
      int codePoint = field.codePointAt(index);
      if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
        break;
      }
      index += Character.charCount(codePoint);
    }
    return index;
  }

  private static void rejectControlCharacters(String content) {
    for (int index = 0; index < content.length(); index++) {
      char character = content.charAt(index);
      if (character < 0x20 && character != '\n' && character != '\r') {
        throw new StayPolicyFileException("Stay policy CSV contains a control character");
      }
    }
  }

  private static StayPolicyFileException malformed(int lineNumber, String reason) {
    return new StayPolicyFileException(
        "Invalid stay policy CSV line " + lineNumber + ": " + reason);
  }
}
