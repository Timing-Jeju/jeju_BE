package com.timingjeju.api.global.staypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.staypolicy.StayPolicyCandidate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class StayPolicyCsvReaderTest {

  private static final UUID PLACE = UUID.fromString("65000000-0000-0000-0000-000000000001");
  @TempDir Path importRoot;

  @Test
  void exact_schema의_category와_place_row만_읽는다() throws IOException {
    Path file =
        write(
            "policy.csv",
            "scope,category,placeId,minutes\n"
                + "category_default,VE,,90\n"
                + "place_override,,"
                + PLACE
                + ",120\n");

    List<StayPolicyCandidate> policies = new StayPolicyCsvReader(importRoot).read(file);

    assertThat(policies)
        .containsExactly(
            StayPolicyCandidate.categoryDefault("VE", 90),
            StayPolicyCandidate.placeOverride(PLACE, 120));
  }

  @Test
  void 상대경로_root밖_symlink과_csv외_확장자를_거부한다() throws IOException {
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
    StayPolicyCsvReader reader = new StayPolicyCsvReader(importRoot);
    Path formula =
        write("formula.csv", "scope,category,placeId,minutes\ncategory_default,=ENV_SECRET,,90\n");
    Path control =
        write("control.csv", "scope,category,placeId,minutes\ncategory_default,V\u0001E,,90\n");
    Path secretColumn =
        write(
            "secret.csv",
            "scope,category,placeId,minutes,userEmail,apiToken,rawPayload\n"
                + "category_default,VE,,90,user@example.test,secret,{}\n");

    assertThatThrownBy(() -> reader.read(formula))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("formula or macro");
    assertThatThrownBy(() -> reader.read(control))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("control character");
    assertThatThrownBy(() -> reader.read(secretColumn))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("exact header");
  }

  private Path write(String name, String content) throws IOException {
    Path file = importRoot.resolve(name);
    Files.writeString(file, content);
    return file;
  }
}
