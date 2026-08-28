package com.timingjeju.api.domain.savedplaces.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.timingjeju.api.domain.savedplaces.model.PresentValue;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacePatchCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PatchSavedPlaceRequest {
  private PresentValue<String> memo = PresentValue.omitted();
  private PresentValue<List<String>> tags = PresentValue.omitted();
  private PresentValue<Integer> priority = PresentValue.omitted();
  private PresentValue<Integer> targetDay = PresentValue.omitted();

  public void setMemo(String value) {
    memo = PresentValue.of(value);
  }

  public void setTags(List<String> value) {
    tags = PresentValue.of(value);
  }

  public void setPriority(Integer value) {
    priority = PresentValue.of(value);
  }

  public void setTargetDay(Integer value) {
    targetDay = PresentValue.of(value);
  }

  @JsonAnySetter
  void unknown(String name, Object value) {
    throw SavedPlaceException.invalidRequest();
  }

  public SavedPlacePatchCommand toCommand() {
    if (!memo.present() && !tags.present() && !priority.present() && !targetDay.present()) {
      throw SavedPlaceException.invalidRequest();
    }
    String normalizedMemo = memo.present() ? SavedPlaceCommand.normalizeMemo(memo.value()) : null;
    List<String> normalizedTags =
        tags.present() ? SavedPlaceCommand.normalizeTags(tags.value()) : null;
    Integer normalizedPriority =
        priority.present() ? SavedPlaceCommand.normalizePriority(priority.value()) : null;
    Integer normalizedTargetDay =
        targetDay.present() ? SavedPlaceCommand.normalizeTargetDay(targetDay.value()) : null;
    return new SavedPlacePatchCommand(
        memo.present() ? PresentValue.of(normalizedMemo) : PresentValue.omitted(),
        tags.present() ? PresentValue.of(normalizedTags) : PresentValue.omitted(),
        priority.present() ? PresentValue.of(normalizedPriority) : PresentValue.omitted(),
        targetDay.present() ? PresentValue.of(normalizedTargetDay) : PresentValue.omitted());
  }
}
