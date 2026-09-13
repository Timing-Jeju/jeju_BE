package com.timingjeju.api.support.postgresql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 테스트 SQL 전송 실패 시 Testcontainers의 producer/consumer 파이프 종료 교착을 피한다. */
class PostgreSqlArchiveContainer extends PostgreSQLContainer {

  PostgreSqlArchiveContainer(DockerImageName image) {
    super(image);
  }

  @Override
  public void copyFileToContainer(Transferable transferable, String containerPath) {
    if (getContainerId() == null) {
      throw new IllegalStateException("파일 복사는 생성된 테스트 컨테이너에서만 가능합니다.");
    }
    try (var archive =
        new TemporaryArchive(Files.createTempFile("timing-jeju-test-copy-", ".tar"))) {
      // Docker가 읽기 시작하기 전에 archive와 EOF를 완성한다. 별도 writer thread가 없다.
      try (var output = new TarArchiveOutputStream(Files.newOutputStream(archive.path()))) {
        output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        output.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        transferable.transferTo(output, containerPath);
      }
      try (var input = Files.newInputStream(archive.path());
          var command = getDockerClient().copyArchiveToContainerCmd(getContainerId())) {
        command.withTarInputStream(input).withRemotePath("/").exec();
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("테스트 컨테이너 SQL archive 전송 실패", exception);
    }
  }

  private record TemporaryArchive(Path path) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      Files.deleteIfExists(path);
    }
  }
}
