package com.timingjeju.api.domain.savedplaces.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.timingjeju.api.domain.savedplaces.model.CanonicalSavedPlaceId;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class CreateSavedPlaceRequest {
  @Schema(
      format = "uuid",
      pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String placeId;

  private String memo;
  private List<String> tags;
  private Integer priority;
  private Integer targetDay;

  public String getPlaceId() {
    return placeId;
  }

  public void setPlaceId(String value) {
    placeId = value;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String value) {
    memo = value;
  }

  public List<String> getTags() {
    return tags;
  }

  public void setTags(List<String> value) {
    tags = value;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer value) {
    priority = value;
  }

  public Integer getTargetDay() {
    return targetDay;
  }

  public void setTargetDay(Integer value) {
    targetDay = value;
  }

  @JsonAnySetter
  void unknown(String name, Object value) {
    throw SavedPlaceException.invalidRequest();
  }

  public SavedPlaceCommand toCommand() {
    if (placeId == null) throw SavedPlaceException.invalidRequest();
    return SavedPlaceCommand.create(
        CanonicalSavedPlaceId.parse(placeId), memo, tags, priority, targetDay);
  }
}
