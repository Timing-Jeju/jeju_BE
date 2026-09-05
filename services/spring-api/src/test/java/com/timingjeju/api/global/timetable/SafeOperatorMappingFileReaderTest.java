package com.timingjeju.api.global.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.timetable.TimetableParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SafeOperatorMappingFileReaderTest {
  @TempDir Path temporary;

  @Test
  void mapping도_anchored_nofollow_handle로_읽고_symlink는_거부한다() throws Exception {
    Path mapping = temporary.resolve("mapping.json");
    Files.writeString(mapping, "{}");
    assertThat(new SafeOperatorMappingFileReader(mapping).read())
        .isEqualTo(Files.readAllBytes(mapping));

    Path outside = Files.createTempFile("operator-mapping", ".json");
    Path link = temporary.resolve("link.json");
    Files.createSymbolicLink(link, outside);
    try {
      assertThatThrownBy(() -> new SafeOperatorMappingFileReader(link).read())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsafe operator mapping file");
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void mapping_loader의_path_entrypoint가_안전한_reader를_공유한다() throws Exception {
    Path oversized = temporary.resolve("mapping.json");
    Files.write(oversized, new byte[SafeOperatorMappingFileReader.MAX_FILE_BYTES + 1]);

    assertThatThrownBy(() -> new OperatorTimetableMappingLoader(new ObjectMapper()).load(oversized))
        .isInstanceOf(TimetableParseException.class)
        .hasMessageContaining("OPERATOR_MAPPING_FILE_UNSAFE");
  }
}
