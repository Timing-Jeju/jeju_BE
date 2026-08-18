package com.timingjeju.api.global.staypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class StayPolicyCsvReaderTest {

  private static final UUID PLACE = UUID.fromString("65000000-0000-0000-0000-000000000001");
  @TempDir Path importRoot;

  @BeforeEach
  void createNestedDirectory() throws IOException {
    Files.createDirectories(importRoot.resolve("nested"));
  }

  @Test
  void exact_schema의_category와_place_row만_읽는다() throws IOException {
    List<StayPolicyCandidate> policies =
        StayPolicyCsvReader.parseContent(
            "scope,category,placeId,minutes\n"
                + "category_default,VE,,90\n"
                + "place_override,,"
                + PLACE
                + ",120\n");

    assertThat(policies)
        .containsExactly(
            StayPolicyCandidate.categoryDefault("VE", 90),
            StayPolicyCandidate.placeOverride(PLACE, 120));
  }

  @Test
  void 상대경로_root밖_symlink과_csv외_확장자를_거부한다() throws IOException {
    assumeTrue(supportsSecureDirectoryStream());
    StayPolicyCsvReader reader = new StayPolicyCsvReader(importRoot);
    Path outside = Files.createTempFile("stay-policy-outside", ".csv");
    Path link = importRoot.resolve("link.csv");
    Files.createSymbolicLink(link, outside);
    Path text = write("policy.txt", "scope,category,placeId,minutes\n");

    assertThatThrownBy(() -> reader.read(Path.of("policy.csv")))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> reader.read(outside))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("root");
    assertThatThrownBy(() -> reader.read(link))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("symbolic link");
    assertThatThrownBy(() -> reader.read(text))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining(".csv");
    Files.deleteIfExists(outside);
  }

  @Test
  void formula_control_character_unknown_column과_사용자_secret_raw_data열을_거부한다() throws IOException {
    assertThatThrownBy(
            () ->
                StayPolicyCsvReader.parseContent(
                    "scope,category,placeId,minutes\ncategory_default,=ENV_SECRET,,90\n"))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("formula or macro");
    assertThatThrownBy(
            () ->
                StayPolicyCsvReader.parseContent(
                    "scope,category,placeId,minutes\ncategory_default,V\u0001E,,90\n"))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("control character");
    assertThatThrownBy(
            () ->
                StayPolicyCsvReader.parseContent(
                    "scope,category,placeId,minutes,userEmail,apiToken,rawPayload\n"
                        + "category_default,VE,,90,user@example.test,secret,{}\n"))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("exact header");
  }

  @Test
  void import_root_아래_중간_경로_symlink가_root밖을_가리키면_읽지_않는다() throws IOException {
    assumeTrue(supportsSecureDirectoryStream());
    Path outsideDirectory = Files.createTempDirectory("stay-policy-outside-directory");
    Path outside = outsideDirectory.resolve("policy.csv");
    Files.writeString(outside, "scope,category,placeId,minutes\ncategory_default,OUTSIDE,,90\n");
    Path linkedDirectory = importRoot.resolve("linked-directory");
    Files.createSymbolicLink(linkedDirectory, outsideDirectory);

    try {
      assertThatThrownBy(
              () -> new StayPolicyCsvReader(importRoot).read(linkedDirectory.resolve("policy.csv")))
          .isInstanceOf(StayPolicyFileException.class)
          .hasMessageContaining("symbolic link");
    } finally {
      Files.deleteIfExists(linkedDirectory);
      Files.deleteIfExists(outside);
      Files.deleteIfExists(outsideDirectory);
    }
  }

  @Test
  void 검증후_open직전_파일이_root밖_symlink로_교체되어도_밖의_내용을_읽지_않는다() throws IOException {
    assumeTrue(supportsSecureDirectoryStream());
    Path file = write("race.csv", "scope,category,placeId,minutes\ncategory_default,SAFE,,90\n");
    Path outside = Files.createTempFile("stay-policy-race-outside", ".csv");
    Files.writeString(outside, "scope,category,placeId,minutes\ncategory_default,OUTSIDE,,90\n");
    StayPolicyCsvReader reader =
        new StayPolicyCsvReader(
            importRoot,
            () -> {
              try {
                Files.delete(file);
                Files.createSymbolicLink(file, outside);
              } catch (IOException exception) {
                throw new IllegalStateException(exception);
              }
            });

    try {
      assertThatThrownBy(() -> reader.read(file))
          .isInstanceOf(StayPolicyFileException.class)
          .hasMessageContaining("symbolic link");
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void open한_중간_directory_component가_root밖_symlink로_교체되어도_anchored_handle만_읽는다()
      throws IOException {
    assumeTrue(supportsSecureDirectoryStream());
    Path directory = Files.createDirectory(importRoot.resolve("safe-directory"));
    Path movedDirectory = importRoot.resolve("opened-directory");
    Path file = directory.resolve("policy.csv");
    Files.writeString(file, "scope,category,placeId,minutes\ncategory_default,SAFE,,90\n");
    Path outsideDirectory = Files.createTempDirectory("stay-policy-component-race");
    Path outside = outsideDirectory.resolve("policy.csv");
    Files.writeString(outside, "scope,category,placeId,minutes\ncategory_default,OUTSIDE,,90\n");
    StayPolicyCsvReader reader =
        new StayPolicyCsvReader(
            importRoot,
            () -> {
              try {
                Files.move(directory, movedDirectory);
                Files.createSymbolicLink(directory, outsideDirectory);
              } catch (IOException exception) {
                throw new IllegalStateException(exception);
              }
            });

    try {
      assertThat(reader.read(file))
          .containsExactly(StayPolicyCandidate.categoryDefault("SAFE", 90));
    } finally {
      Files.deleteIfExists(directory);
      Files.deleteIfExists(movedDirectory.resolve("policy.csv"));
      Files.deleteIfExists(movedDirectory);
      Files.deleteIfExists(outside);
      Files.deleteIfExists(outsideDirectory);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "=CMD",
        "+CMD",
        "-CMD",
        "@CMD",
        " =CMD",
        "\u2003=CMD",
        "\u00a0=CMD",
        "\t=CMD",
        "\u0001=CMD"
      })
  void formula_prefix와_leading_whitespace_control_변형을_모두_거부한다(String category) throws IOException {
    assertThatThrownBy(
            () ->
                StayPolicyCsvReader.parseContent(
                    "scope,category,placeId,minutes\ncategory_default," + category + ",,90\n"))
        .isInstanceOf(StayPolicyFileException.class);
  }

  @Test
  void secure_directory_handle을_지원하지_않는_provider는_fail_closed한다() throws IOException {
    assumeFalse(supportsSecureDirectoryStream());
    Path file =
        write("unsupported.csv", "scope,category,placeId,minutes\ncategory_default,VE,,90\n");

    assertThatThrownBy(() -> new StayPolicyCsvReader(importRoot).read(file))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("does not support secure path access");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void Linux_CI_provider는_secure_directory_handle을_반드시_지원한다() throws IOException {
    assertThat(supportsSecureDirectoryStream()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void OS와_무관한_secure_directory_contract로_nested_component와_NOFOLLOW_open을_검증한다()
      throws IOException {
    SecureDirectoryStream<Path> root = mock(SecureDirectoryStream.class);
    SecureDirectoryStream<Path> nested = mock(SecureDirectoryStream.class);
    BasicFileAttributeView view = mock(BasicFileAttributeView.class);
    BasicFileAttributes attributes = mock(BasicFileAttributes.class);
    SeekableByteChannel channel = mock(SeekableByteChannel.class);
    Path nestedComponent = Path.of("nested");
    Path fileComponent = Path.of("policy.csv");
    byte[] content =
        "scope,category,placeId,minutes\ncategory_default,SAFE,,90\n"
            .getBytes(StandardCharsets.UTF_8);
    AtomicBoolean firstRead = new AtomicBoolean(true);
    when(root.newDirectoryStream(nestedComponent, LinkOption.NOFOLLOW_LINKS)).thenReturn(nested);
    when(nested.getFileAttributeView(
            fileComponent, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS))
        .thenReturn(view);
    when(view.readAttributes()).thenReturn(attributes);
    when(attributes.isRegularFile()).thenReturn(true);
    when(channel.size()).thenReturn((long) content.length);
    when(channel.read(any(ByteBuffer.class)))
        .thenAnswer(
            invocation -> {
              if (!firstRead.getAndSet(false)) {
                return -1;
              }
              invocation.<ByteBuffer>getArgument(0).put(content);
              return content.length;
            });
    when(nested.newByteChannel(eq(fileComponent), anySet())).thenReturn(channel);
    StayPolicyCsvReader reader = new StayPolicyCsvReader(importRoot, () -> {}, ignored -> root);

    assertThat(reader.read(importRoot.resolve("nested/policy.csv")))
        .containsExactly(StayPolicyCandidate.categoryDefault("SAFE", 90));
    verify(root).newDirectoryStream(nestedComponent, LinkOption.NOFOLLOW_LINKS);
    verify(nested)
        .newByteChannel(
            eq(fileComponent),
            eq(Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
  }

  private boolean supportsSecureDirectoryStream() throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(importRoot)) {
      return stream instanceof SecureDirectoryStream<?>;
    }
  }

  private Path write(String name, String content) throws IOException {
    Path file = importRoot.resolve(name);
    Files.writeString(file, content);
    return file;
  }
}
