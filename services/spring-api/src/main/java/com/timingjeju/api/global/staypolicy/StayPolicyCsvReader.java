package com.timingjeju.api.global.staypolicy;

import com.timingjeju.api.application.staypolicy.StayPolicyCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class StayPolicyCsvReader {

  private static final String HEADER = "scope,category,placeId,minutes";
  private static final long MAX_FILE_BYTES = 1_048_576;
  private static final int MAX_ROWS = 10_000;

  private final Path importRoot;

  public StayPolicyCsvReader(Path importRoot) {
    this.importRoot = realDirectory(importRoot);
  }

  public List<StayPolicyCandidate> read(Path requestedFile) {
    Path file = validatedFile(requestedFile);
    String content;
    try {
      if (Files.size(file) > MAX_FILE_BYTES) {
        throw new StayPolicyFileException("Stay policy CSV exceeds 1 MiB");
      }
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new StayPolicyFileException("Stay policy CSV could not be read", exception);
    }
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

  private Path validatedFile(Path requestedFile) {
    if (requestedFile == null || !requestedFile.isAbsolute()) {
      throw new StayPolicyFileException("Stay policy CSV path must be absolute");
    }
    Path absolute = requestedFile.toAbsolutePath().normalize();
    if (!absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
      throw new StayPolicyFileException("Stay policy import supports .csv files only");
    }
    if (Files.isSymbolicLink(absolute)) {
      throw new StayPolicyFileException("Stay policy CSV must not be a symbolic link");
    }
    Path real;
    try {
      real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException exception) {
      throw new StayPolicyFileException(
          "Stay policy CSV must be a regular readable file", exception);
    }
    if (!real.startsWith(importRoot)) {
      throw new StayPolicyFileException(
          "Stay policy CSV must stay inside the configured import root");
    }
    if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
      throw new StayPolicyFileException("Stay policy CSV must be a regular file");
    }
    return real;
  }

  private static Path realDirectory(Path root) {
    if (root == null || !root.isAbsolute()) {
      throw new StayPolicyFileException("Stay policy import root must be absolute");
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
      String stripped = field.strip();
      if (!stripped.isEmpty() && "=+@".indexOf(stripped.charAt(0)) >= 0) {
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
