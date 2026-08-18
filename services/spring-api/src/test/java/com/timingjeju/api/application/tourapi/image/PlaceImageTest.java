package com.timingjeju.api.application.tourapi.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PlaceImageTest {

  @Test
  void http_https_이미지_URL은_도메인_검증을_통과한다() {
    PlaceImage imageWithHttp =
        new PlaceImage(
            "ID-1",
            "http://img.example.test/1.jpg",
            "https://img.example.test/1-small.jpg",
            null,
            null,
            null,
            null,
            1);

    assertThat(imageWithHttp.imageUrl()).isEqualTo("http://img.example.test/1.jpg");
    assertThat(imageWithHttp.thumbnailUrl()).isEqualTo("https://img.example.test/1-small.jpg");
  }

  @Test
  void 비정상_URL은_PlaceImage_생성시_거부한다() {
    assertThatThrownBy(
            () ->
                new PlaceImage(
                    "ID-2", "ftp://img.example.test/1.jpg", null, null, null, null, null, 1))
        .isInstanceOf(PlaceImageImportException.class);

    assertThatThrownBy(
            () -> new PlaceImage("ID-3", "/relative/path.jpg", null, null, null, null, null, 1))
        .isInstanceOf(PlaceImageImportException.class);

    assertThatThrownBy(
            () ->
                new PlaceImage(
                    "ID-4", "https://user@img.example.test/1.jpg", null, null, null, null, null, 1))
        .isInstanceOf(PlaceImageImportException.class);

    assertThatThrownBy(
            () ->
                new PlaceImage(
                    "ID-5",
                    "https://img.example.test/1.jpg#section",
                    null,
                    null,
                    null,
                    null,
                    null,
                    1))
        .isInstanceOf(PlaceImageImportException.class);
  }
}
