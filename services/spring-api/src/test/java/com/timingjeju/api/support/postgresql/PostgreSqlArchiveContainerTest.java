package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@Tag("unit")
class PostgreSqlArchiveContainerTest {

  @Test
  void 큰_SQL도_파일명과_권한과_내용을_완성한_archive로_전송한다() throws Exception {
    DockerClient docker = mock(DockerClient.class);
    CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class);
    when(docker.copyArchiveToContainerCmd("fixture-container")).thenReturn(copy);
    when(copy.withRemotePath("/")).thenReturn(copy);
    AtomicReference<byte[]> uploaded = new AtomicReference<>();
    doAnswer(
            invocation -> {
              InputStream stream = invocation.getArgument(0);
              uploaded.set(stream.readAllBytes());
              return copy;
            })
        .when(copy)
        .withTarInputStream(any(InputStream.class));
    byte[] sql = "select '한글';\n".repeat(10000).getBytes(StandardCharsets.UTF_8);

    container(docker).copyFileToContainer(Transferable.of(sql, 0644), "/tmp/fixture.sql");

    try (var tar = new TarArchiveInputStream(new ByteArrayInputStream(uploaded.get()))) {
      var entry = tar.getNextEntry();
      assertThat(entry.getName()).isEqualTo("tmp/fixture.sql");
      assertThat(entry.getMode()).isEqualTo(0644);
      assertThat(entry.getSize()).isEqualTo(sql.length);
      assertThat(tar.readAllBytes()).isEqualTo(sql);
      assertThat(tar.getNextEntry()).isNull();
    }
    verify(copy).exec();
    verify(copy).close();
  }

  @Test
  void Docker가_본문을_읽기전에_실패해도_파이프_종료대기없이_원래_실패를_반환한다() {
    DockerClient docker = mock(DockerClient.class);
    CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class);
    when(docker.copyArchiveToContainerCmd("fixture-container")).thenReturn(copy);
    when(copy.withRemotePath("/")).thenReturn(copy);
    when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);
    RuntimeException failure = new IllegalStateException("fixture upload rejected");
    doThrow(failure).when(copy).exec();
    byte[] sql = new byte[256 * 1024];

    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () ->
            assertThatThrownBy(
                    () ->
                        container(docker)
                            .copyFileToContainer(Transferable.of(sql), "/tmp/fixture.sql"))
                .isSameAs(failure));
    verify(copy).close();
  }

  private static PostgreSqlArchiveContainer container(DockerClient docker) {
    return new PostgreSqlArchiveContainer(
        DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")) {
      @Override
      public DockerClient getDockerClient() {
        return docker;
      }

      @Override
      public String getContainerId() {
        return "fixture-container";
      }
    };
  }
}
