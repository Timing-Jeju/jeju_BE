package com.timingjeju.api.domain.profile.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageControllerSourceContractTest {

  @Test
  void request_JSON_reader는_trailing_token거부를_공유_mapper_default와_무관하게_고정한다() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/com/timingjeju/api/domain/profile/controller/ProfileImageController.java"));

    assertThat(source)
        .contains("DeserializationFeature.FAIL_ON_TRAILING_TOKENS")
        .contains("readerFor(ProfileImageRequest.class)");
  }
}
