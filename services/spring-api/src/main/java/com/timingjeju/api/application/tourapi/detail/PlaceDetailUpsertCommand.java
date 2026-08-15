package com.timingjeju.api.application.tourapi.detail;

import java.time.Instant;
import java.util.Objects;

public record PlaceDetailUpsertCommand(
    String contentId,
    PlaceDetailCommon common,
    PlaceDetailIntro intro,
    DetailLineage commonLineage,
    DetailLineage introLineage,
    Instant fetchedAt) {
  public PlaceDetailUpsertCommand {
    if (contentId == null || contentId.isBlank()) {
      throw new IllegalArgumentException("contentId는 필수입니다.");
    }
    common = Objects.requireNonNull(common, "common은 필수입니다.");
    intro = Objects.requireNonNull(intro, "intro는 필수입니다.");
    commonLineage = Objects.requireNonNull(commonLineage, "commonLineage는 필수입니다.");
    introLineage = Objects.requireNonNull(introLineage, "introLineage는 필수입니다.");
    fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    if (!"detailCommon2".equals(commonLineage.operationKey())
        || !"detailIntro2".equals(introLineage.operationKey())
        || !contentId.equals(common.contentId())
        || !contentId.equals(intro.contentId())
        || !common.contentTypeId().equals(intro.contentTypeId())) {
      throw new IllegalArgumentException("상세 command의 식별자 또는 operation이 일치하지 않습니다.");
    }
  }
}
