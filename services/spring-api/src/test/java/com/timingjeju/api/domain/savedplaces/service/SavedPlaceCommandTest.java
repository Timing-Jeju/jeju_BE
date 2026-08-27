package com.timingjeju.api.domain.savedplaces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.savedplaces.dto.PatchSavedPlaceRequest;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SavedPlaceCommandTest {
  private static final UUID PLACE_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");

  @Test
  void create는_기본값과_trim_NFC_tag정렬을_canonicalize한다() {
    var command = SavedPlaceCommand.create(PLACE_ID, " 방문 ", List.of("선택", "동쪽"), null, null);

    assertThat(command.memo()).isEqualTo("방문");
    assertThat(command.tags()).containsExactly("동쪽", "선택");
    assertThat(command.priority()).isZero();
    assertThat(command.targetDay()).isNull();
  }

  @Test
  void create는_normalize후_중복_tag와_control문자를_422로_거부한다() {
    assertThatThrownBy(
            () -> SavedPlaceCommand.create(PLACE_ID, null, List.of("동쪽", "동쪽"), 0, null))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("SAVED_PLACE_CONSTRAINT_VIOLATION");
    assertThatThrownBy(() -> SavedPlaceCommand.create(PLACE_ID, "비밀\n값", List.of(), 0, null))
        .isInstanceOf(SavedPlaceException.class);
  }

  @Test
  void patch는_omitted와_explicit_null을_구별하고_collection을_replace한다() {
    PatchSavedPlaceRequest request = new PatchSavedPlaceRequest();
    request.setMemo(null);
    request.setTags(List.of("동쪽"));
    request.setPriority(null);
    var patch = request.toCommand();

    assertThat(patch.memo().present()).isTrue();
    assertThat(patch.memo().value()).isNull();
    assertThat(patch.tags().value()).containsExactly("동쪽");
    assertThat(patch.priority().value()).isZero();
    assertThat(patch.targetDay().present()).isFalse();
  }

  @Test
  void 길이는_UTF16이_아닌_Unicode_code_point로_계산하고_tag도_code_point순으로_정렬한다() {
    String supplementary = "😀";
    var command =
        SavedPlaceCommand.create(
            PLACE_ID,
            supplementary.repeat(2000),
            List.of(supplementary.repeat(50), "\uE000"),
            0,
            null);

    assertThat(command.memo()).hasSize(4000);
    assertThat(command.tags()).containsExactly("\uE000", supplementary.repeat(50));
    assertThatThrownBy(
            () ->
                SavedPlaceCommand.create(PLACE_ID, supplementary.repeat(2001), List.of(), 0, null))
        .isInstanceOf(SavedPlaceException.class);
  }

  @Test
  void memo도_NFC로_정규화해_DB_boundary와_일치한다() {
    var command = SavedPlaceCommand.create(PLACE_ID, " 동쪽 ", List.of(), 0, null);

    assertThat(command.memo()).isEqualTo("동쪽");
  }

  @Test
  void trim은_DB_btrim과_같은_ASCII_space만_제거하고_EM_SPACE는_보존한다() {
    var command =
        SavedPlaceCommand.create(
            PLACE_ID, " \u2003메모\u2003 ", List.of(" \u2003태그\u2003 "), 0, null);

    assertThat(command.memo()).isEqualTo("\u2003메모\u2003");
    assertThat(command.tags()).containsExactly("\u2003태그\u2003");
  }
}
